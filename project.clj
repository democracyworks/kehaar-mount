(defproject kehaar-mount "1.0.3-SNAPSOPT"
  :description "Helpful function and macros for using kehaar with mount"
  :url "https://github.com/democracyworks/kehaar-mount"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.0" :scope "provided"]
                 [org.clojure/core.async "1.0.567" :scope "provided"]
                 [democracyworks/kehaar "1.0.3" :scope "provided"]
                 [mount "0.1.16" :scope "provided"]]
  :repl-options {:init-ns kehaar-mount.core})
