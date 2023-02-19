(ns bot
  (:require
   [clojure.core.async    :as a]
   [clojure.java.shell :as sh]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [cprop.core            :refer [load-config]]
   [datoteka.core :as fs]
   [discljord.connections :as c]
   [discljord.events :as e]
   [discljord.messaging.specs   :as ms]
   [discljord.messaging   :as m])
  (:gen-class))

(defonce state (atom nil))
(def latex-body "\\documentclass{article}%s\\begin{document}%s\\end{document}")

(defn- create-latex-body [input extra-packages]
  (format
   latex-body
   (str/join "" (map #(format "\\usepackage{%s}" %) (str/split extra-packages #",")))
   input))

(defn- get-option [option options]
  (->> options
       (filter #(= (:name %) option))
       first
       :value))

(defn handle-ready
  [_event-type _event-data]
  (log/info
   (a/<!!
    (m/create-guild-application-command!
     (:messaging @state)
     (:application @state)
     (:guild @state)
     "latex-image"
     "Compile latex input to an image"
     {:options
      [{:type 3
        :name "input"
        :description "LaTeX input to compile"
        :min-length 1
        :required true}
       {:type 3
        :name "extra-packages"
        :description "Comma-separated list of extra packages to include"}]})))
  (log/info "Connection ready"))

(defn handle-latex-image
  [options]
  (let [input (get-option "input" options)
        extra-packages (get-option "extra-packages" options)
        dir (fs/create-tempdir "latex-bot-")]
    (sh/with-sh-dir dir
      (spit (fs/path dir "input.tex") (create-latex-body input (or extra-packages "")))
      (log/info "Compiling\n" (sh/sh "tectonic" "-Z" "shell-escape" "input.tex"))
      (log/info "Splitting into pages" (sh/sh "pdftoppm" "input.pdf" "page" "-png"))
      (log/info "Cropping to content" (sh/sh "convert" "page-1.png" "-trim" "+repage" "page-1.png"))
      [:file (fs/file (fs/path dir "page-1.png"))])))

(def ^:private interaction-handlers
  {"latex-image" #'handle-latex-image})

(defn handle-interaction
  [_event-type {:keys [id token] :as event-data}]
  (when-let [handler (interaction-handlers (get-in event-data [:data :name]))]
    (apply
     m/create-interaction-response!
     (:messaging @state)
     id
     token
     (ms/interaction-response-types :channel-message-with-source)
     (handler (get-in event-data [:data :options])))))

(def ^:private handlers
  {:interaction-create [#'handle-interaction]
   :ready          [#'handle-ready]})

(defn -main [& args]
  (let [{:keys [token guild-id application-id]} (load-config)]

    (log/info "Starting bot...")
    (when (nil? @state)
      (let [event-channel (a/chan 100)
            bot-connection (c/connect-bot! token event-channel :intents #{:guilds :guild-messages})
            messaging-connection (m/start-connection! token)]
        (reset! state {:connection bot-connection
                       :event event-channel
                       :messaging messaging-connection
                       :guild guild-id
                       :application application-id})

        (try
          (log/info "Clearing up old attempts")
          (map fs/delete (fs/list-dir fs/*tmp-dir* "latex-bot*"))
          (log/info "Setting up message handlers")
          (e/message-pump! event-channel (partial e/dispatch-handlers #'handlers))

          (finally
            (log/info "Exiting")
            (m/stop-connection! messaging-connection)
            (c/disconnect-bot!  bot-connection)
            (map a/close! [event-channel])
            (reset! state nil)))))))
