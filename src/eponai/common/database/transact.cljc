(ns eponai.common.database.transact
  (:require [eponai.common.format :as f]
    #?(:clj
            [datomic.api :as d]
       :cljs [datascript.core :as d])
            [taoensso.timbre #?(:clj :refer :cljs :refer-macros) [trace debug error]]))

(defn transact
  "Transact a collecion of entites into datomic.
  Throws ExceptionInfo if transaction failed."
  [conn txs]
  (try
    (trace "Transacting: " txs)
    (let [ret @(d/transact conn txs)]
      ret)
    (catch #?(:clj Exception :cljs :default) e
      (let [#?@(:clj  [msg (.getMessage e)]
                :cljs [msg (.-message e)])]
        (error "Transaction error: " e)
        (throw (ex-info msg
                        {:cause     ::transaction-error
                         :data      {:conn conn
                                     :txs  txs}
                         :message   msg
                         :exception e
                         #?@(:clj [:status :eponai.server.http/service-unavailable])}))))))

(defn transact-map
  "Transact a map into datomic, where the keys names the entities to be transacted for developer convenience.

  Will call transact on (vals m)."
  [conn m]
  (transact conn (vals m)))

(defn transact-one
  "Transact a single entity or transaction into datomic"
  [conn value]
  (transact conn [value]))

(defn transact-schemas [conn schemas]
  (doseq [schema schemas]
    (transact conn schema)))

(defn tx-mutation [mutation-uuid]
  {:db/id            (d/tempid :db.part/tx)
   :tx/mutation-uuid mutation-uuid
   ;; :tx/reverted refers to if the optimistic changes has been reverted or not.
   #?@(:cljs [:tx/reverted false])})

;; TODO: Warn that these are deprecated.

(defn mutate
  [conn mutation-uuid txs]
  (transact conn txs))

(defn mutate-one
  [conn mutation-uuid value]
  (transact-one conn value))

(defn mutate-map
  [conn mutation-uuid m]
  (transact-map conn m))
