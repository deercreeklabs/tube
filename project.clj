(defproject deercreeklabs/tube "0.1.2-SNAPSHOT"
  :description "Clojure/Clojurescript websocket client and server library."
  :url "http://www.deercreeklabs.com"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}
  :lein-release {:scm :git
                 :deploy-via :clojars}

  :main deercreeklabs.tube.server
  :global-vars {*warn-on-reflection* true}
  :pedantic? :abort

  :profiles
  {:dev
   {:plugins
    [[lein-ancient "0.6.10"]
     [lein-cljsbuild "1.1.7" :exclusions [org.clojure/clojure]]
     [lein-cloverage "1.0.9" :exclusions [org.clojure/clojure]]
     ;;[lein-doo "0.1.7"]
     ;; Because of confusion with a defunct project also called
     ;; lein-release, we exclude lein-release from lein-ancient.
     [lein-release "1.0.9" :upgrade false :exclusions [org.clojure/clojure]]]
    ;; :dependencies
    ;; [[doo "0.1.7"]]
    }
   :uberjar {:aot :all
             :jvm-opts ^:replace ["-server" "-XX:+AggressiveOpts"]}}

  :plugins
  [[lein-doo "0.1.7" :exclusions [org.clojure/clojure
                                  org.clojure/clojurescript]]]

  :dependencies
  [[cljsjs/nodejs-externs "1.0.4-1"]
   [cljsjs/pako "0.2.7-0"]
   [clj-time "0.14.0"]
   [com.andrewmcveigh/cljs-time "0.5.1"]
   [com.google.guava/guava "23.0" :exclusions [com.google.code.findbugs/jsr305]]
   [com.taoensso/timbre "4.10.0"]
   [com.fzakaria/slf4j-timbre "0.3.7"]
   [org.clojure/clojure "1.8.0"]
   [org.clojure/clojurescript "1.9.908"]
   [org.clojure/core.async "0.3.443"]
   [org.java-websocket/Java-WebSocket "1.3.4"]
   [prismatic/schema "1.1.6"]
   [primitive-math "0.1.6"]
   [stylefruits/gniazdo "1.0.1"]]

  :cljsbuild
  {:builds
   [{:id "node-test-none"
     :source-paths ["src" "test"]
     :notify-command ["node" "target/test/node_test_none/test_main.js"]
     :compiler
     {:optimizations :none
      :parallel-build true
      :main "deercreeklabs.node-test-runner"
      :target :nodejs
      :output-to "target/test/node_test_none/test_main.js"
      :output-dir "target/test/node_test_none"
      :source-map true}}
    {:id "node-test-adv"
     :source-paths ["src" "test"]
     :notify-command ["node" "target/test/node_test_adv/test_main.js"]
     :compiler
     {:optimizations :advanced
      :parallel-build true
      :main "deercreeklabs.node-test-runner"
      :target :nodejs
      :static-fns true
      :output-to  "target/test/node_test_adv/test_main.js"
      :output-dir "target/test/node_test_adv"
      :source-map "target/test/node_test_adv/map.js.map"}}
    {:id "node-test-simple"
     :source-paths ["src" "test"]
     :notify-command ["node" "target/test/node_test_simple/test_main.js"]
     :compiler
     {:optimizations :simple
      :parallel-build true
      :main "deercreeklabs.node-test-runner"
      :target :nodejs
      :static-fns true
      :output-to  "target/test/node_test_simple/test_main.js"
      :output-dir "target/test/node_test_simple"
      :source-map "target/test/node_test_simple/map.js.map"}}
    {:id "browser-test-none"
     :source-paths ["src" "test"]
     :compiler
     {:optimizations :none
      :parallel-build true
      :main "deercreeklabs.doo-test-runner"
      :output-to "target/test/browser_test_none/test_main.js"
      :output-dir "target/test/browser_test_none"
      :source-map true}}
    {:id "browser-test-simple"
     :source-paths ["src" "test"]
     :compiler
     {:optimizations :simple
      :parallel-build true
      :main "deercreeklabs.doo-test-runner"
      :output-to "target/test/browser_test_simple/test_main.js"
      :output-dir "target/test/browser_test_simple"
      :source-map "target/test/browser_test_simple/map.js.map"}}
    {:id "build-simple"
     :source-paths ["src"]
     :compiler
     {:optimizations :simple
      :parallel-build true
      :static-fns true
      :output-to  "target/build_simple/tube.js"
      :output-dir "target/build_simple"
      :source-map "target/build_simple/map.js.map"}}]}

  :aliases
  {"auto-test-cljs" ["do"
                     "clean,"
                     "cljsbuild" "auto" "node-test-none"]
   "auto-test-cljs-adv" ["do"
                         "clean,"
                         "cljsbuild" "auto" "node-test-adv"]
   "auto-test-cljs-simple" ["do"
                            "clean,"
                            "cljsbuild" "auto" "node-test-simple"]
   "build-simple" ["do"
                   "clean,"
                   "cljsbuild" "once" "build-simple"]})
