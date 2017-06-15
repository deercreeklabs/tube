(defproject deercreeklabs/tube "0.1.11-SNAPSHOT"
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
     [lein-cljsbuild "1.1.6"]
     ;; Because of confusion with a defunct project also called
     ;; lein-release, we exclude lein-release from lein-ancient.
     [lein-release "1.0.9" :upgrade false :exclusions [org.clojure/clojure]]]}}

  :dependencies
  [[bidi "2.1.1"]
   [cljsjs/nodejs-externs "1.0.4-1"]
   [cljsjs/pako "0.2.7-0"]
   [com.google.guava/guava "22.0"]
   [com.taoensso/timbre "4.10.0"]
   [http-kit "2.2.0"]
   [org.clojure/clojure "1.8.0"]
   [org.clojure/clojurescript "1.9.562"]
   [org.clojure/core.async "0.3.443"]
   [prismatic/schema "1.1.6"]
   [stylefruits/gniazdo "1.0.0"]]

  ;; :global-vars {*warn-on-reflection* true}

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
