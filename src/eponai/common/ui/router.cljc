(ns eponai.common.ui.router
  (:require
    [om.next :as om :refer [defui]]
    [om.dom]
    [taoensso.timbre :refer [error debug info]]
    [clojure.walk :as walk]
    #?(:cljs [eponai.web.modules :as modules])
    [eponai.client.utils :as utils]))

(def dom-app-id "the-sulo-app")

(defn normalize-route
  "We need to normalize our routes now that we have namespaced route matches.
  The namespaced route matches help us set new routes."
  [route]
  (if-let [ns (namespace route)]
    (keyword ns)
    route))

(defmulti route->component normalize-route)

(defmethod route->component :default
  [_]
  nil)

#?(:clj
   (defmacro register-component [route component]
     (let [in-cljs? (boolean (:ns &env))]
       `(do
          (defmethod route->component ~route [~'_] {:component ~component})
          ~(when in-cljs? `(eponai.web.modules/set-loaded! ~route))))))

(def routes [:unauthorized :index :store :checkout :browse
             :shopping-bag :login :sell :product :live :help
             :user :store-dashboard :coming-soon])

(defui Router
  static om/IQuery
  (query [this]
    [:query/current-route
     {:routing/app-root (into {}
                              (map (fn [route]
                                     (let [{:keys [component]} (route->component route)]
                                       [route (if component
                                                (om/get-query component)
                                                [])])))
                              routes)}])
  Object
  #?(:cljs
     (shouldComponentUpdate [this props state]
                            (let [next-route (some-> (utils/shouldComponentUpdate-next-props props)
                                                     (get-in [:query/current-route :route])
                                                     (normalize-route))
                                  ret (and
                                        (or (nil? next-route)
                                            (modules/loaded-route? (om/shared this :shared/modules) next-route))
                                        (utils/shouldComponentUpdate-om this props state))]
                              (debug "should-component update: " ret)
                              ret)))
  (render [this]
    (let [{:keys [routing/app-root query/current-route]} (om/props this)
          route (normalize-route (:route current-route))
          {:keys [factory component]} (route->component route)
          _ (when (nil? component)
              (error "Sorry. No component found for route: " route
                     ". Make sure to implement multimethod router/route->component in your component's namespace"
                     " for route: " route
                     ". You also have to call (router/register-component <route> <component>) at the end of your component"
                     ". You also have to require your component's namespace in env/client/dev/env/web/main.cljs"
                     ". We're making it this complicated because we want module code splitting."))
          factory (or factory (om/factory component))]
      (factory app-root))))
