(ns eponai.common.ui.common
  (:require
    [eponai.client.routes :as routes]
    [eponai.common.ui.elements.callout :as callout]
    [eponai.common.ui.dom :as dom]
    [eponai.common.ui.elements.css :as css]
    [eponai.common.ui.elements.grid :as grid]
    [eponai.common.ui.elements.menu :as menu]
    [taoensso.timbre :refer [debug error]]
    [om.next :as om]
    [eponai.web.ui.photo :as photo]
    [eponai.web.social :as social]
    [eponai.web.ui.button :as button]
    [eponai.common.ui.search-bar :as search-bar]
    [clojure.string :as string]))

(defn render-shipping [shipping opts]
  (let [{:shipping/keys [address]
         full-name      :shipping/name} shipping
        {:shipping.address/keys [street locality postal region country]} address]
    (dom/p (css/add-class :sl-shipping)
           (when (not-empty full-name)
             [
              (dom/span (css/add-class :sl-shipping--name) full-name)
              (dom/br nil)])
           (when (some? street)
             [
              (dom/span (css/add-class :sl-shipping--address) (str street))
              (dom/br nil)])
           (dom/span (css/add-class :sl-shipping--address) (string/join ", " (filter not-empty [(str locality " " postal) region (or (:country/name country)
                                                                                                                                     (:country/code country))]))))))

(defn order-status-element [order]
  (let [status (:order/status order)]
    (when (some? status)
      (dom/span
        (->> (css/add-class :sl-orderstatus)
             (css/add-class (str "sl-orderstatus--" (name status))))
        (cond (#{:order.status/paid :order.status/created} status)
              "New"
              (#{:order.status/fulfilled} status)
              "Shipped"
              (#{:order.status/returned} status)
              "Returned"
              (#{:order.status/canceled} status)
              "Canceled")))))

(defn follow-button [opts]
  (dom/p (css/add-class :follow-button-container)
         (dom/a
           (->> (css/button opts)
                (css/add-classes [:disabled :follow-button :sulo-dark :hollow]))
           (dom/span nil "Follow"))))

(defn contact-button [opts]
  (dom/a (->> (css/button-hollow opts)
              (css/add-classes [:contact-button :sl-tooltip]))
         (dom/span nil "Contact")
         (dom/span (css/add-class :sl-tooltip-text) "Coming soon")))

(defn modal [opts & content]
  (let [{:keys [classes on-close size require-close?]} opts]
    (dom/div
      (->> {:id      "reveal-overlay"
            :onClick #(when (= "reveal-overlay" (.-id (.-target %)))
                       (when (and on-close (not require-close?))
                         (on-close)))}
           (css/add-class :reveal-overlay))
      (dom/div
        (css/add-class (str "reveal " (when (some? size) (name size))) {:classes classes})
        (when on-close
          (dom/a
            (css/add-class :close-button {:onClick on-close})
            (dom/span nil "x")))
        content))))

(defn loading-spinner [opts & content]
  (dom/div
    (css/add-class :sl-spinner-overlay)
    (dom/div
      (css/add-class :sl-spinner)
      (dom/img {:src "/assets/img/auth0-icon.png"}))
    content))


(defn content-section [{:keys [href target class sizes]} header content footer]
  (dom/div
    (->> {:classes [class]}
         (css/add-classes [:content :section])
         (css/text-align :center))
    (dom/h3 (css/add-class :section-header) header)

    content
    ))

(defn city-banner [component locations]
  (let [{:sulo-locality/keys [title photo]} locations]
    (dom/div
      (css/add-class :intro-header {:id "sulo-city-banner"})
      (photo/cover
        {:photo-id (:photo/id photo)}
        (grid/row
          (css/align :bottom)
          (grid/column
            (grid/column-size {:small 12 :medium 6})
            (dom/h1
              (css/add-class :header)
              (dom/i {:className "fa fa-map-marker"})
              (dom/span nil title)))
          (grid/column
            nil
            (dom/div
              (css/add-class :input-container)
              (search-bar/->SearchBar {:ref             (str ::search-bar-ref)
                                       :placeholder     "What are you looking for?"
                                       :mixpanel-source "index"
                                       :classes         [:drop-shadow]
                                       :locations locations})
              (button/button
                (->> (button/expanded {:onClick (fn []
                                                  (let [search-bar (om/react-ref component (str ::search-bar-ref))]
                                                    (when (nil? search-bar)
                                                      (error "NO SEARCH BAR :( " component))
                                                    (search-bar/trigger-search! search-bar)))})
                     (css/add-classes [:drop-shadow]))
                (dom/span nil "Search")))))))))

(defn sell-on-sulo [component]
  (let [{:query/keys [locations]} (om/props component)]
    (dom/div
      (->>
           (css/add-classes [:sell-on-sulo :banner :section])
           (css/text-align :center))
      (dom/h2 (css/add-classes [:sulo-dark :jumbo-header :banner]) "Share your story")
      (dom/p (css/add-classes [:lead :jumbo-lead :banner :sulo-dark])
             (dom/span nil "Sell your products while connecting with your customers in new ways using LIVE streams. All in one place."))
      (grid/row-column
        (->> (css/add-class :section-footer)
             (css/text-align :center))
        (button/button
          (css/add-classes [:sulo-dark] {:href (routes/url :sell)}) "Open your LIVE shop"))
      )))

(defn mobile-app-banner [component]
  (dom/div
    (->>
      (css/add-classes [:mobile-app-banner :banner :section]))
    (grid/row
      (grid/columns-in-row {:small 1 :medium 2})
      (grid/column
        nil
        (dom/p (css/add-classes [:lead :pre-header]) (dom/i nil "Coming soon"))
        (dom/h2 (css/add-classes [:sulo-dark :jumbo-header :banner]) "Don't miss a moment")
        (dom/p (css/add-classes [:lead :jumbo-lead :banner :sulo-dark])
               (dom/span nil "Watch your favorite brands on your phone from anywhere.")))
      (grid/column
        nil
        (photo/photo {:src "/assets/img/placeit.png"})))
    ))


(defn is-new-order? [component]
  (let [{:query/keys [current-route]} (om/props component)]
    (nil? (get-in current-route [:route-params :order-id]))))

(defn is-order-not-found? [component]
  (let [{:query/keys [current-route order]} (om/props component)]
    (and (some? (get-in current-route [:route-params :order-id]))
         (nil? order))))

(defn order-not-found [component return-href]
  (let [{:query/keys [current-route]} (om/props component)
        {:keys [order-id store-id]} (:route-params current-route)]
    (grid/row-column
      nil
      (callout/callout
        (->> (css/text-align :center)
             (css/add-class :not-found))
        (dom/h1 nil "Not found")
        (dom/p nil (dom/i {:classes ["fa fa-times-circle fa-2x"]}))
        (dom/p nil
               (dom/span nil "Order with number ")
               (dom/strong nil (str "#" order-id))
               (dom/span nil " was not found in ")
               (dom/a {:href return-href}
                      (dom/strong nil "your orders")))))))

(defn payment-logos []
  {"Visa"             "icon-cc-visa"
   "American Express" "icon-cc-amex"
   "MasterCard"       "icon-cc-mastercard"
   "Discover"         "icon-cc-discover"
   "JCB"              "icon-cc-jcb"
   "Diners Club"      "icon-cc-diners"
   "Unknown"          "icon-cc-unknown"})