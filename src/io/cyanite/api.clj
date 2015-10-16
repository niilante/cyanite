(ns io.cyanite.api
  "Cyanite's 'HTTP interface"
  (:require [com.stuartsierra.component :as component]
            [cheshire.core              :as json]
            [io.cyanite.engine.rule     :as rule]
            [io.cyanite.engine          :as engine]
            [io.cyanite.engine.queue    :as q]
            [io.cyanite.engine.buckets  :as b]
            [io.cyanite.index           :as index]
            [io.cyanite.store           :as store]
            [io.cyanite.query           :as query]
            [io.cyanite.http            :as http]
            [io.cyanite.utils           :refer [nbhm assoc-if-absent! now!]]
            [clojure.tools.logging      :refer [info debug error]]
            [clojure.string             :refer [lower-case blank?]]))

(defn parse-time
  "Parse an epoch into a long"
  [time-string]
  (try (Long/parseLong time-string)
       (catch NumberFormatException _
         (error "wrong time format: " time-string)
         nil)))

(def routes
  "Dead simple router"
  [[:paths #"^/paths.*"]
   [:metrics #"^/metrics.*"]
   [:ping  #"^/ping/?"]])

(defn match-route
  "Predicate which returns the matched elements"
  [{:keys [uri path-info] :as request} [action re]]
  (when (re-matches re (or path-info uri))
    action))

(defn assoc-route
  "Find which route matches if any and store the appropriate action
   in the :action field of the request"
  [request]
  (assoc request :action (some (partial match-route request) routes)))

(defmulti dispatch
  "Dispatch on action, as found by assoc-route"
  :action)

(defn process
  "Process a request. Handle errors and report them"
  [request store index engine]
  (try
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body    (json/generate-string
               (dispatch (assoc request
                                :store  store
                                :index  index
                                :engine engine)))}
    (catch Exception e
      (let [{:keys [status body suppress? exception]} (ex-data e)]
        (when-not suppress?
          (error e "could not process request")
          (when exception
            (error exception "request process exception")))
        {:status (or status 500)
         :headers {"Content-Type" "application/json"}
         :body    (json/generate-string
                   {:message (or body (.getMessage e))})}))))

(defmethod dispatch :ping
  [_]
  {:response "pong"})

(defmethod dispatch :paths
  [{{:keys [query]} :params index :index}]
  (debug "path fetch request for:" (pr-str query))
  (index/prefixes index (if (blank? query) "*" query)))

(defmethod dispatch :query
  [{{:keys [from to query]} :params :keys [index store engine]}]
  (let [from  (or (parse-time from)
                  (throw (ex-info "missing from parameter"
                                  {:suppress? true :status 400})))
        to    (or (parse-time to) (now!))]
    (query/run-query! store index engine from to query)))

(defmethod dispatch :metrics
  [{{:keys [from to path agg]} :params :keys [index store engine]}]
  (debug "metric fetch request for:" (pr-str path))
  (let [from  (or (parse-time from)
                  (throw (ex-info "missing from parameter"
                                  {:suppress? true :status 400})))
        to    (or (parse-time to) (now!))
        paths (->> (mapcat (partial index/prefixes index)
                           (if (sequential? path) path [path]))
                   (map :path)
                   (map (partial engine/resolution engine from))
                   (remove nil?))]
    (store/query! store from to paths)))

(defmethod dispatch :default
  [_]
  (throw (ex-info "unknown action" {:status 404 :suppress? true})))

(defn make-handler
  "Yield a ring-handler for a request"
  [store index engine]
  (fn [request]
    (-> request
        (assoc-route)
        (process store index engine))))

(defrecord Api [options server service store index engine]
  component/Lifecycle
  (start [this]
    (if (:disabled options)
      this
      (let [handler (make-handler store index engine)
            server  (http/run-server options handler)]
        (assoc this :server server))))
  (stop [this]
    (when (fn? server)
      (server))
    (assoc this :server nil)))
