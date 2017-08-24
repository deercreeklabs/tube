(defproject deercreeklabs/tube "0.1.2-SNAPSHOT"
  :description "Clojure/Clojurescript websocket client and server library."
  :url "http://www.deercreeklabs.com"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}
  :lein-release {:scm :git
                 :deploy-via :clojars}
  :profiles
  {:dev
   {:plugins
    [[lein-ancient "0.6.10"]
     [lein-cljsbuild "1.1.7" :exclusions [org.clojure/clojure]]
     [lein-cloverage "1.0.9" :exclusions [org.clojure/clojure]]
     ;; Because of confusion with a defunct project also called
     ;; lein-release, we exclude lein-release from lein-ancient.
     [lein-release "1.0.9" :upgrade false :exclusions [org.clojure/clojure]]]}
   :uberjar {:aot :all
             :jvm-opts ^:replace ["-server" "-XX:+AggressiveOpts"]}}

  :pedantic? :abort

  :dependencies
  [[bidi "2.1.2"]
   [cljsjs/nodejs-externs "1.0.4-1"]
   [cljsjs/pako "0.2.7-0"]
   [clj-time "0.14.0"]
   [com.andrewmcveigh/cljs-time "0.5.1"]
   [com.google.guava/guava "23.0" :exclusions [com.google.code.findbugs/jsr305]]
   [com.taoensso/timbre "4.10.0"]
   [http-kit "2.2.0"]
   [org.clojure/clojure "1.8.0"]
   [org.clojure/clojurescript "1.9.908"]
   [org.clojure/core.async "0.3.443"]
   [prismatic/schema "1.1.6"]
   [stylefruits/gniazdo "1.0.1"]]

  :global-vars {*warn-on-reflection* true}

  :main deercreeklabs.tube.server

  :cljsbuild
  {:builds
   [{:id "node-test-none"
     :source-paths ["src" "test"]
     :notify-command ["node" "target/test/node_test_none/test_main.js"]
     :compiler
     {:optimizations :none
      :main "deercreeklabs.test-runner"
      :target :nodejs
      :output-to "target/test/node_test_none/test_main.js"
      :output-dir "target/test/node_test_none"
      :source-map true}}
    {:id "node-test-adv"
     :source-paths ["src" "test"]
     :notify-command ["node" "target/test/node_test_adv/test_main.js"]
     :compiler
     {:optimizations :advanced
      :main "deercreeklabs.test-runner"
      :target :nodejs
      :static-fns true
      :output-to  "target/test/node_test_adv/test_main.js"
      :output-dir "target/test/node_test_adv"
      :source-map "target/test/node_test_adv/map.js.map"}}]}

  :aliases
  {"auto-test-cljs" ["do"
                     "clean,"
                     "cljsbuild" "auto" "node-test-none"]
   "auto-test-cljs-adv" ["do"
                         "clean,"
                         "cljsbuild" "auto" "node-test-adv"]})
