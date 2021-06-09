# kehaar-mount

A Clojure library to facilitate using the mount [mount][mount] state management
library with DW's [kehaar][kehaar] for communicating with RabbitMQ.

## Rationale

Kehaar has its own state management system in the `kehaar.configured`
namespace. Unfortunately, due to the way it binds queue publishers to channels,
and queue consumers to functions, this system doesn't allow reloading a
namespace, and requires restarting the repl for changes to take effect.

Mount makes state reloadable within the same repl session, and ensures that
pieces of state are started in the right order. Mount also restarts any states
defined in a namespace when you reload the namespace, making the reloadable
repl workflow almost transparent.

We have tried using `kehaar.configured` and `mount` together with limited
success. Wrapping the entire kehaar state in mount does make it possible to
reload kehaar, but requires reloading the namespace where the kehaar state
lives (typically `my-service.queue`, which is written once and never touched
again), rather than the namespace where the consumer handlers live (typically
`my-service.handlers`, which you probably have open if you're working on a
handler). Additionally, kehaar requires that channels and handler functions
exist before calling `kehaar.configured/init!`, a dependency that is hidden
from mount and can lead to confusing errors if namespaces are not required in
the correct order.

`kehaar-mount` is structured so that each RabbitMQ publisher and consumer is a
separate mount state, which depends on the connection state (rather than kehaar
state depending on the handlers and channels). This plays well with mount's
automatic reloading feature, since states are more granular and are located
closer to their implementation.


## Example

### The kehaar.configured approach

This is roughly the boilerplace that's included in all our services as of
2021-06-09.

```clj
;; resources/config.edn
{:rabbitmq
 {:connection {...}
  :kehaar {:incoming-services [{:queue "my-service.foo"
                                :f my-service.handlers/foo
                                :response true}]
           :external-services [{:queue "your-service.bar"
                                :channel my-service.channels/your-service-bar-chan
                                :response true
                                :timeout 3000}]}}}


;; my-service/src/my_service/queue.clj
(ns my-service.queue
  (:require [kehaar.configured :as kehaar]
            [kehaar.rabbitmq]
            [langohr.core :as rmq]))

(defn initialize [config]
  (let [rabbit-cfg (get-in config [:rabbitmq :connection])
        kehaar-cfg (get-in config [:rabbitmq :kehaar])
        connection (kehaar.rabbitmq/connect-with-retries rabbit-cfg 5)
        kehaar-resources (kehaar/init! connection kehaar-config)]
    {:connections [connection]
     :kehaar-resources kehaar-resources}))

(defn close-resources! [resources]
  (doseq [resource resources]
    (when-not (rmq/closed? resource) (rmq/close resource))))

(defn close-all! [{:keys [connections kehaar-resources]}]
  (kehaar/shutdown! kehaar-resources)
  (close-resources! connections))


;; my-service/src/my_service/channels.clj
(ns my-service.channels
  (:require [clojure.core.async :as async]))

(defonce your-service-bar-chan (async/chan))


;; my-service/src/my_service/handlers.clj
(ns my-service
  (:require [kehaar.wire-up :as wire-up]
            [my-service.channels :as channels]))

(def your-service-bar
  (wire-up/async->chan channels/your-service-bar-chan))

(defn foo* [bar-fn message]
  ...)

(def foo (partial foo* your-service-bar message))
```


### The kehaar-mount approach

Note that this is only a little less code than above. The main benefit is that
state is handled better, and it is simpler to reload this code in a repl.

```clj
;; resources/config.edn
{:rabbitmq
 {:connection {...}
  :kehaar {:incoming-services [{:queue "my-service.foo"
                                :f my-service.handlers/foo
                                :response true}]
           :external-services [{:queue "your-service.bar"
                                ;; the one change from above
                                :f my-service.publishers/your-service-bar
                                :response true
                                :timeout 3000}]}}}


;; my-service/src/my_service/queue.clj
(ns my-service.queue
  (:require [kehaar-mount.core :as km]
            [kehaar.rabbitmq]
            [langohr.core :as rmq]
            [mount.core :refer [defstate]]
            ;; another mount state
            [my-service.config :refer [config]]))

;; TODO: should km/start! and km/stop! also manage the rabbitmq connection?
;; It would make things simpler and reduce some boilerplate in this ns
(defstate connection
  :start (kehaar.rabbitmq/connect-with-retries
          (get-in config [:rabbitmq :connection]) 5)
  :stop (rmq/close connection))

(defstate kehaar
  :start (km/start! connection (get-in config [:rabbitmq :kehaar]))
  :stop (km/stop! kehaar))


;; my-service/src/my_service/publishers.clj
(ns my-service.publishers
  (:require [my-service.queue :refer [kehaar]]
            [kehaar-mount.core :refer [defpublisher]]))

(defpublisher your-service-bar :kehaar kehaar)


;; my-service/src/my_service/handlers.clj
(ns my-service
  (:require [kehaar-mount.core :refer [defconsumer]]
            [my-service.publishers :as publishers]
            [my-service.queue :refer [kehaar]]))

(defn foo* [bar-fn message]
  ...)

(defconsumer foo
  :f (partial foo* publishers/your-service-bar message)
  :kehaar kehaar)
```

## License

Copyright © Democracy Works, Inc.

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.


[mount]: https://github.com/tolitius/mount
[kehaar]: https://github.com/democracyworks/kehaar
