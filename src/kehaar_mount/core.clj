(ns kehaar-mount.core
  (:require [kehaar-mount.impl :as impl]
            [mount.core :refer [defstate]]))

(defn start!
  "Starts a kehaar mount state.

  This is a replacement for `kehaar.configured/init!` that is intended to be
  used in `mount.core/defstate`."
  [connection config]
  (impl/start! connection config))

(defn stop!
  "Stops a kehaar state started by `start!`"
  [kehaar-state]
  (impl/stop! kehaar-state))

(defmacro defconsumer
  "Defines (using `mount.core/defstate`) a kehaar consumer.

  `:start` initializes kehaar consumer state and returns the handler function.

  `:stop` cleans up kehaar state for this consumer.

  Options:

  :kehaar -- the kehaar state
  :f      -- a handler function
  :id     -- a kehaar config id (default: fully-qualified name)"
  [name & {:keys [f kehaar id]}]
  (let [id (or id (symbol (str *ns*) name))]
    `(defstate ~name
       :start (let [f# ~f
                    state# (impl/start-consumer! ~kehaar f# ~id)]
                (with-meta f# {::state state}))
       :stop (impl/stop-consumer! (::state (meta ~name))))))

(defmacro defpublisher
  "Defines (using `mount.core/defstate`) a kehaar publisher.

  `:start` creates an `async/chan` for the publisher, initializes kehaar
  publisher state, and returns a wired-up function for the asunc/chan.

  `:stop` closes the channel and cleans up kehaar state for this publisher.

  Options:

  :kehaar -- the kehaar state
  :id     -- a kehaar config id (default: fully-qualified name)"
  [name & {:keys [kehaar id]}]
  (let [id (or id (symbol (str *ns*) name))]
    `(defstate ~name
       :start (let [state# (impl/start-publisher! ~kehaar ~id)
                    f# (:f state#)]
                (with-meta f# {::state state}))
       :stop (impl/stop-publisher! (::state (meta ~name))))))
