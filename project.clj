(defproject tesla-solar-charger "1.0.0"
  :description "FIXME: write description"
  :url "https://github.com/nqeng/tesla-solar-charger"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [
                 [org.clojure/tools.cli "1.0.219"]
                 [org.clojure/clojure "1.11.1"]
                 [clj-http "3.12.3"]
                 [cheshire "5.11.0"]
                 [lynxeyes/dotenv "1.1.0"]
                 [org.clojure/data.priority-map "1.1.0"]
                 [org.clojure/core.async "1.6.681"]
                 [better-cond "2.1.5"]
                 [etaoin "1.0.40"]
                 ]
  :main ^:skip-aot tesla-solar-charger.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
