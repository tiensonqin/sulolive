(ns eponai.server.parser.mutate
  (:require
    [clojure.core.async :as async]
    [eponai.common.database.transact :as transact]
    [eponai.common.format :as format]
    [eponai.common.parser :as parser :refer [mutate message]]
    [eponai.common.validate :as validate]
    [taoensso.timbre :as timbre :refer [debug]]
    [eponai.server.api :as api]
    [eponai.common.database.pull :as p]
    [datomic.api :as d]
    [eponai.common.format :as common.format]
    [environ.core :refer [env]]
    [eponai.server.external.facebook :as fb]
    [eponai.server.auth.credentials :as a]))

(defmacro defmutation
  "Creates a message and mutate defmethod at the same time.
  The body takes two maps. The first body is the message and the
  other is the mutate.
  The :return and :exception key in env is only available in the
  message body."
  [sym args message-body mutate-body]
  `(do
     (defmethod message (quote ~sym) ~args ~message-body)
     (defmethod mutate (quote ~sym) ~args ~mutate-body)))

;; ------------------- Transaction --------------------

(defmutation transaction/create
  [{:keys [state auth] :as env} k input-transaction]
  {:success (str "Transaction: \"" (:transaction/title input-transaction) "\" created!")
   :error   "Error creating transaction"}
  {:action (fn []
             (debug "transaction/create with params:" input-transaction)
             (validate/validate env k {:transaction input-transaction
                                       :user-uuid   (:username auth)})
             (let [transaction (common.format/transaction input-transaction)
                   currency-chan (async/chan 1)
                   tx-report (transact/transact-one state transaction)]
               (async/go (async/>! currency-chan (:transaction/date transaction)))
               (assoc tx-report :currency-chan currency-chan)))})

(defmethod mutate 'transaction/edit
  [{:keys [state auth] :as env} k {:keys [transaction/uuid] :as transaction}]
  (debug "transaction/edit with params:" transaction)
  {:action (fn []
             (validate/validate env k {:transaction transaction
                                       :user-uuid (:username auth)})
             (debug "validated transaction")
             (let [txs (format/transaction-edit transaction)]
               (debug "editing transaction: " uuid " txs: " txs)
               (transact/transact state txs)))})

;; ----------------- project --------------------

(defmethod mutate 'project/save
  [{:keys [state auth]} _ params]
  (debug "project/save with params: " params)
  {:action (fn []
             (let [user-ref [:user/uuid (:username auth)]
                   project (format/project user-ref params)
                   dashboard (format/dashboard (:db/id project) params)]
               (transact/transact state [project dashboard])))})

(defmethod mutate 'project/share
  [{:keys [state]} _ {:keys [project/uuid user/email] :as params}]
  (debug "project/save with params: " params)
  {:action (fn []
             (api/share-project state uuid email))})

;; --------------- Widget ----------------

(defmethod mutate 'widget/create
  [{:keys [state auth] :as env} k params]
  (debug "widget/create with params: " params)
  {:action (fn []
             (validate/validate env k {:widget    params
                                       :user-uuid (:username auth)})
             (let [widget (format/widget-create params)]
               (transact/transact-one state widget)))})

(defmethod mutate 'widget/edit
  [{:keys [state]} _ params]
  (debug "widget/edit with params: " params)
  {:action (fn []
             (let [widget (format/widget-edit params)]
               (transact/transact state widget)))
   :remote true})

(defmethod mutate 'widget/delete
  [{:keys [state]} _ params]
  (debug "widget/delete with params: " params)
  (let [widget-uuid (:widget/uuid params)]
    {:action (fn []
               (transact/transact-one state [:db.fn/retractEntity [:widget/uuid widget-uuid]]))
     :remote true}))

;; ---------------- Dashboard ----------------

(defmethod mutate 'dashboard/save
  [{:keys [state]} _ {:keys [widget-layout] :as params}]
  (debug "dashboard/save with params: " params)
  {:action (fn []
             (transact/transact state (format/add-tempid widget-layout)))})

;; ------------------- User account related ------------------

(defmethod mutate 'settings/save
  [{:keys [state]} _ {:keys [currency user] :as params}]
  (debug "settings/save with params: " params)
  {:action (fn []
             (transact/transact-one state [:db/add [:user/uuid (:user/uuid user)] :user/currency [:currency/code currency]]))})


(defmethod mutate 'stripe/subscribe
  [{:keys [state auth stripe-fn]} _ p]
  (debug "stripe/subscribe with params:" p)
  (let [db (d/db state)
        stripe-eid (p/one-with db {:where '[[?u :user/uuid ?useruuid]
                                            [?e :stripe/user ?u]]
                                   :symbols {'?user-uuid (:username auth)}})
        stripe-account (when stripe-eid
                         (p/pull db [:stripe/customer
                                     {:stripe/subscription [:stripe.subscription/id]}]
                                 stripe-eid))]
    {:action (fn []
               (api/stripe-subscribe state stripe-fn stripe-account p))}))

(defmethod mutate 'stripe/trial
  [{:keys [state auth stripe-fn]} _ _]
  (let [user (p/pull (d/db state) [:user/email :stripe/_user] [:user/uuid (:username auth)])]
    {:action (fn []
               (api/stripe-trial state stripe-fn user))}))

(defmethod mutate 'stripe/cancel
  [{:keys [state auth] :as env} _ p]
  (debug "stripe/cancel with params:" p)
  (let [db (d/db state)
        eid (p/one-with db {:where   '[[?u :user/uuid ?user-uuid]
                                       [?e :stripe/user ?u]]
                            :symbols {'?user-uuid (:username auth)}})
        stripe-account (when eid
                         (p/pull db [:stripe/customer
                                     {:stripe/subscription [:stripe.subscription/id]}] eid))]
    {:action (fn []
               (api/stripe-cancel env stripe-account))}))

;; ############# Session mutations #################

(defmethod mutate 'session.signin/email
  [{:keys [state]} k {:keys [device] :as params}]
  (debug "signup/email with params:" params)
  {:action (fn []
             ;; TODO: Need a more generic way of specifying required parameters for mutations.
             (when-not device
               (throw (ex-info (str "No device specified for " k
                                    ". Specify :device with either :web, :ios or whatever"
                                    " send email needs.")
                               {:mutation k :params params})))
             (-> (api/signin state (:input-email params))
                 (assoc :device device)))})

(defmethod mutate 'session.signin.email/verify
  [{:keys [auth]} _ {:keys [verify-uuid] :as p}]
  (debug "session.signin.email/verify with params: " p)
  {:action (fn []
             {:auth (some? auth)})})

(defmethod mutate 'session.signin/facebook
  [{:keys [auth]} _ {:keys [access-token user-id] :as p}]
  (debug "session.signin/facebook with params: " p)
  {:action (fn []
             {:auth (some? auth)})})

(defmethod mutate 'session.signin/activate
  [{:keys [auth]} _ {:keys [user-uuid user-email] :as p}]
  (debug "session.signin/activate with params: " p)
  {:action (fn []
             {:auth (some? auth)})})

(defmethod mutate 'session/signout
  [{:keys [auth]} _ p]
  (debug "session/signout with params: " p)
  {:action (fn []
             {:auth (some? auth)})})