(ns kehaar-mount.impl
  (:require [clojure.core.async :as async]
            [kehaar.configured :as k.configured]
            [kehaar.jobs :as k.jobs]
            [kehaar.wire-up :as k.wire-up]))

;;; Functions for searching a kehaar.configured map for a service id
;; Service ids are specified using `:id` or `:f` in the config map

(defn flatten-config
  [kehaar-configured-map]
  (flatten
   (for [[section configs] kehaar-configured-map]
     (map #(-> %
               (assoc ::section section)
               (assoc ::id (some % [:id :f])))
          configs))))

(defn- assert-single-config [id s]
  (case (count s)
    0 (throw (ex-info (str "No kehaar config found for id: " id)
                      {:id id, :config (doall s)}))
    1 s
    (throw (ex-info (str "Multiple kehaar configs found for id: " id)
                    {:id id, :config (doall s)}))))

(defn- find-consumer [kehaar-state id]
  (->> (:config kehaar-state)
       (filter (comp #{id} ::id))
       (filter (comp #{:incoming-services :incoming-events :incoming-jobs} ::section))
       (assert-single-config id)
       (first)))

(defn- find-publisher [kehaar-state id]
  (->> (:config kehaar-state)
       (filter (comp #{id} ::id))
       (filter (comp #{:external-services :outgoing-events :outgoing-jobs} ::section))
       (assert-single-config id)
       (first)))

;;; Kehaar state

(defn start!
  [connection config]
  (let [global-config (select-keys config [:event-exchanges])
        service-config (dissoc config :event-exchanges)]
    {:state (k.configured/init! connection global-config)
     :connection connection
     :config (flatten-config service-config)}))

(defn stop!
  [kehaar-state]
  (k.configured/shutdown! (:state kehaar-state)))

;;; Consumers

(defn init-consumer [kehaar-state cfg]
  (let [connection (:connection kehaar-state)]
    (case (::section cfg)
      :incoming-services (k.configured/init-incoming-service! connection cfg)
      :incoming-events (k.configured/init-incoming-event! connection cfg)
      :incoming-jobs (k.configured/init-incoming-job! connection cfg))))

(defn start-consumer!
  "Starts a consumer, returning a map of states."
  [kehaar-state handler-fn id]
  (let [config (-> (find-consumer kehaar-state id)
                   (assoc :f handler-fn))]
    {:kehaar-part (init-consumer kehaar-state config)}))

(defn stop-consumer!
  "Stops a consumer started with `start-consumer!`"
  [{:keys [kehaar-part]}]
  (k.configured/shutdown-part! kehaar-part))

;;; Publishers

(defn init-publisher [kehaar-state cfg]
  (let [connection (:connection kehaar-state)]
    (case (::section cfg)
      :external-services (k.configured/init-external-service! connection cfg)
      :outgoing-events (k.configured/init-outgoing-event! connection cfg)
      :outgoing-jobs (k.configured/init-outgoing-job! connection cfg))))

(defn wire-up-publisher [cfg]
  (case (::section cfg)
    :external-services (if (:response cfg)
                         (k.wire-up/async->fn (:channel cfg))
                         (k.wire-up/async->fire-and-forget-fn (:channel cfg)))
    :outgoing-events (partial async/>!! (:channel cfg))
    :outgoing-jobs (k.jobs/async->job (:jobs-chan cfg))))

(defn start-publisher!
  "Starts a publisher, returning a map of states and a wired-up publisher
  function `:f`."
  [kehaar-state id]
  (let [channel (async/chan)
        config (-> (find-publisher kehaar-state id)
                   (assoc :channel channel
                          ;; jobs uses this key instead of :channel
                          :jobs-chan channel))]
    {:kehaar-part (init-publisher kehaar-state config)
     :f (wire-up-publisher config)
     :channel channel}))

(defn stop-publisher!
  "Stops a publisher started with `start-publisher!`"
  [{:keys [kehaar-part channel]}]
  (k.configured/shutdown-part! kehaar-part)
  (async/close! channel))
