(ns redgenes.components.idresolver.views
  (:require [reagent.core :as reagent]
            [re-frame.core :as re-frame :refer [subscribe dispatch]]
            [json-html.core :as json-html]
            [dommy.core :as dommy :refer-macros [sel sel1]]
            [redgenes.components.idresolver.events]
            [redgenes.components.icons :as icons]
            [redgenes.components.idresolver.subs]
            [redgenes.components.lighttable :as lighttable]))

;;; TODOS:

;We need to handler more than X results :D right now 1000 results would ALL show on screen. Eep.
;;submit button needed
;;results preview needed


(defn ex []
  (let [active-mine (subscribe [:current-mine])
        mines (subscribe [:mines])
        example-text (:idresolver-example ((:id @active-mine) @mines))]
example-text))

(def separators (set ".,; "))

(defn splitter
  "Splits a string on any one of a set of strings."
  [string]
  (->> (clojure.string/split string (re-pattern (str "[" (reduce str separators) "\\r?\\n]")))
       (remove nil?)
       (remove #(= "" %))))

(defn has-separator?
  "Returns true if a string contains any one of a set of strings."
  [str]
  (some? (some separators str)))

(defn controls []
  (let [results  (subscribe [:idresolver/results])
        matches  (subscribe [:idresolver/results-matches])
        selected (subscribe [:idresolver/selected])]
    (fn []
      [:div.btn-toolbar.controls
       [:button.btn.btn-warning
        {:class    (if (nil? @results) "disabled")
         :on-click (fn [] (dispatch [:idresolver/clear]))}
        "Clear all"]
       [:button.btn.btn-warning
        {:class    (if (empty? @selected) "disabled")
         :on-click (fn [] (dispatch [:idresolver/delete-selected]))}
        (str "Remove selected (" (count @selected) ")")]
       [:button.btn.btn-primary.btn-raised
        {:class    (if (nil? @results) "disabled")
         :on-click (fn [] (if (some? @results) (dispatch [:idresolver/analyse true])))}
        "View Results"]])))


;

(defn submit-input [input] (dispatch [:idresolver/resolve (splitter input)]))

(defn input-box []
  (reagent/create-class
  (let [val (reagent/atom nil)
        timer (reagent/atom nil)]
    {:reagent-render (fn []
      [:input#identifierinput.freeform
       {:type         "text"
        :placeholder  "Type or paste identifiers here..."
        :value        @val
        :on-key-press (fn [e]
                        (let [keycode (.-charCode e)
                              input   (.. e -target -value)]
                          (cond (= keycode 13)
                                (do
                                  (reset! val "")
                                  (submit-input input)))))
        :on-change    (fn [e]
                        (let [input (.. e -target -value)]
                          (if (has-separator? input)
                            (do
                              (reset! val "")
                              (submit-input input))
                            (do (reset! val input)))))}])
     :component-did-mount (fn [this] (.focus (reagent/dom-node this)))})))


(defn input-item-duplicate []
  "Input control. allows user to resolve when an ID has matched more than one object."
  (fn [[oid data]]
    [:span [:span.dropdown
     [:span.dropdown-toggle
      {:type        "button"
       :data-toggle "dropdown"}
      (:input data)
      [:span.caret]]
     (into [:ul.dropdown-menu]
           (map (fn [result]
                  [:li
                   {:on-click (fn [e]
                                (.preventDefault e)
                                (dispatch [:idresolver/resolve-duplicate
                                           (:input data)
                                           result]))}
                   [:a (-> result :summary :symbol)]]) (:matches data)))]]))

(defn get-icon [icon-type]
  (case icon-type
    :MATCH [:i.fa.fa-check.MATCH]
    :UNRESOLVED [:svg.icon.icon-sad.UNRESOLVED [:use {:xlinkHref "#icon-sad"}]]
    :DUPLICATE [:i.fa.fa-clone.DUPLICATE]
    :TYPE_CONVERTED [:i.fa.fa-random.TYPE_CONVERTED]
    :OTHER [:svg.icon.icon-arrow-right.OTHER [:use {:xlinkHref "#icon-arrow-right"}]]
    [:i.fa.fa-cog.fa-spin.fa-1x.fa-fw]))

(def reasons
  {:TYPE_CONVERTED "we're searching for genes and you input a protein (or vice versa)."
   :OTHER " the synonym you input is out of date."})

(defn input-item-converted [original results]
  (let [new-primary-id (get-in results [:matches 0 :summary :primaryIdentifier])
        conversion-reason ((:status results) reasons)]
    [:span {:title (str "You input '" original "', but we converted it to '" new-primary-id "', because " conversion-reason)}
     original " -> " new-primary-id]
))

(defn input-item [{:keys [input] :as i}]
  "visually displays items that have been input and have been resolved as known or unknown IDs (or currently are resolving)"
  (let [result   (subscribe [:idresolver/results-item input])
        selected (subscribe [:idresolver/selected])]
    (reagent/create-class
      {:component-did-mount
       (fn [])
       :reagent-render
       (fn [i]
         (let [result-vals (second (first @result))
               class (if (empty? @result)
                       "inactive"
                       (name (:status result-vals)))
               class (if (some #{input} @selected) (str class " selected") class)]
           [:div.id-resolver-item-container
            {:class (if (some #{input} @selected) "selected")}
            [:div.id-resolver-item
             {:class    class
              :on-click (fn [e]
                          (.preventDefault e)
                          (.stopPropagation e)
                          (dispatch [:idresolver/toggle-selected input]))}
             [get-icon (:status result-vals)]
             (case (:status result-vals)
               :DUPLICATE [input-item-duplicate (first @result)]
               :TYPE_CONVERTED [input-item-converted (:input i) result-vals]
               :OTHER [input-item-converted (:input i) result-vals]
               :MATCH [:span (:input i)]
               [:span (:input i)])
              ]]))})))

(defn input-items []
  (let [bank (subscribe [:idresolver/bank])]
    (fn []
      (into [:div.input-items]
        (map (fn [i]
           ^{:key (:input i)} [input-item i]) (reverse @bank))))))

(defn parse-files [files]
  (dotimes [i (.-length files)]
    (let [rdr      (js/FileReader.)
          the-file (aget files i)]
      (set! (.-onload rdr)
            (fn [e]
              (let [file-content (.-result (.-target e))
                    file-name    (if (= ";;; " (.substr file-content 0 4))
                                   (let [idx (.indexOf file-content "\n\n")]
                                     (.slice file-content 4 idx))
                                   (.-name the-file))]
                (submit-input file-content))))
      (.readAsText rdr the-file))))

(defn handle-drag-over [state-atom evt]
  (reset! state-atom true)
  (.stopPropagation evt)
  (.preventDefault evt)
  (set! (.-dropEffect (.-dataTransfer evt)) "copy"))

(defn handle-drop-over [state-atom evt]
  (reset! state-atom false)
  (.stopPropagation evt)
  (.preventDefault evt)
  (let [files (.-files (.-dataTransfer evt))]
    (parse-files files)))

(defn drag-and-drop-prompt []
  [:div.upload-file
   [:svg.icon.icon-file [:use {:xlinkHref "#icon-file"}]]
    [:p "All your identifiers in a text file? Try dragging and dropping it here, or "
      [:label.browseforfile {:on-click (fn [e] (.stopPropagation e))};;otherwise it focuses on the typeable input
       [:input
        {:type "file"
         :multiple true
         :on-click (fn [e] (.stopPropagation e)) ;;otherwise we just end up focusing on the input on the left/top.
         :on-change (fn [e] (parse-files (.-files (.-target e)))
                      )}]
       ;;this input isn't visible, but don't worry, clicking on the label is still accessible. Even the MDN says it's ok. True story.
       "browse for a file"]]])

(defn input-div []
  (let [drag-state (reagent/atom false)]
    (fn []
      [:div.resolvey
       [:div#dropzone1
       {
        :on-drop       (partial handle-drop-over drag-state)
        :on-click      (fn [evt]
                         (.preventDefault evt)
                         (.stopPropagation evt)
                         (dispatch [:idresolver/clear-selected])
                         (.focus (sel1 :#identifierinput)))
        :on-drag-over  (partial handle-drag-over drag-state)
        :on-drag-leave (fn [] (reset! drag-state false))
        :on-drag-end   (fn [] (reset! drag-state false))
        :on-drag-exit  (fn [] (reset! drag-state false))}
       [:div.eenput
        {:class (if @drag-state "dragging")}
        [:div.idresolver
          [input-items]
          [input-box]
         [controls]]
        [drag-and-drop-prompt]
        ]]
       ])))

(defn stats []
  (let [bank       (subscribe [:idresolver/bank])
        no-matches (subscribe [:idresolver/results-no-matches])
        matches    (subscribe [:idresolver/results-matches])
        type-converted (subscribe [:idresolver/results-type-converted])
        duplicates (subscribe [:idresolver/results-duplicates])
        other      (subscribe [:idresolver/results-other])]
    (fn []
        ;;goodness gracious this could use a refactor
        [:div.legend
         [:h3 "Legend & Stats:"]
         [:div.results
            [:div.MATCH {:tab-index -5}
              [:div.type-head [get-icon :MATCH]
                [:span.title "Matches"]
                [:svg.icon.icon-question [:use {:xlinkHref "#icon-question"}]]]
              [:div.details [:span.count (count @matches)]
                [:p "The input you entered was successfully matched to a known ID"]
               ]
             ]
            [:div.TYPE_CONVERTED {:tab-index -4}
              [:div.type-head [get-icon :TYPE_CONVERTED]
                [:span.title "Converted"]
                [:svg.icon.icon-question [:use {:xlinkHref "#icon-question"}]]]
              [:div.details [:span.count (count @type-converted)]
                 [:p "Input protein IDs resolved to gene (or vice versa)"]]
             ]
           [:div.OTHER {:tab-index -2}
             [:div.type-head [get-icon :OTHER]
             [:span.title "Synonyms"]
             [:svg.icon.icon-question [:use {:xlinkHref "#icon-question"}]]]
             [:div.details [:span.count (count @other)]
             [:p "The ID you input matches an old synonym of an ID. We've used the most up-to-date one instead."]]]
          [:div.DUPLICATE {:tab-index -3}
              [:div.type-head  [get-icon :DUPLICATE]
                [:span.title "Partial\u00A0Match"]
                [:svg.icon.icon-question [:use {:xlinkHref "#icon-question"}]]]
              [:div.details [:span.count (count @duplicates)]
                [:p "The ID you input matched more than one item. Click on the down arrow beside IDs with this icon to fix this."]]]
            [:div.UNRESOLVED {:tab-index -1}
              [:div.type-head [get-icon :UNRESOLVED]
                [:span.title "Not\u00A0Found"]
                [:svg.icon.icon-question [:use {:xlinkHref "#icon-question"}]]]
              [:div.details [:span.count (count @no-matches)]
              [:p "The ID you input isn't one that's known for your chosen organism."]]]
        ]]

      )))

(defn debugger []
  (let [everything (subscribe [:idresolver/everything])]
    (fn []
      [:div (json-html/edn->hiccup @everything)])))

(defn spinner []
  (let [resolving? (subscribe [:idresolver/resolving?])]
    (fn []
      (if @resolving?
        [:i.fa.fa-cog.fa-spin.fa-1x.fa-fw]
        [:i.fa.fa-check]))))

(defn selected []
  (let [selected (subscribe [:idresolver/selected])]
    (fn []
      [:div "selected: " (str @selected)])))

(defn delete-selected-handler [e]
  (let [keycode (.-charCode e)]
    (cond
      (= keycode 127) (dispatch [:idresolver/delete-selected])
      :else nil)))

(defn key-down-handler [e]
  (case (.. e -keyIdentifier)
    "Control" (dispatch [:idresolver/toggle-select-multi true])
    "Shift" (dispatch [:idresolver/toggle-select-range true])
    nil))

(defn key-up-handler [e]
  (case (.. e -keyIdentifier)
    "Control" (dispatch [:idresolver/toggle-select-multi false])
    "Shift" (dispatch [:idresolver/toggle-select-range false])
    nil))

(defn attach-body-events []

  (dommy/unlisten! (sel1 :body) :keypress delete-selected-handler)
  (dommy/listen! (sel1 :body) :keypress delete-selected-handler)

  (dommy/unlisten! (sel1 :body) :keydown key-down-handler)
  (dommy/listen! (sel1 :body) :keydown key-down-handler)

  (dommy/unlisten! (sel1 :body) :keyup key-up-handler)
  (dommy/listen! (sel1 :body) :keyup key-up-handler))

(defn preview [result-count]
  ""
  (let [query             (subscribe [:results/query])
        service           (:service @(subscribe [:current-mine]))]
    [:div
     [:h4.title "Results preview:"
      [:small.pull-right "Showing " [:span.count (min 5 result-count)] " of " [:span.count result-count] " Total Good Identifiers. "
        (cond (> result-count 0)
          [:a {:on-click
            (fn [] (dispatch [:idresolver/analyse true]))}
            "View all >>"])
       ]]
     [lighttable/main {:query      @query
                      :service    service
                      :no-repeats true}]]
))

(defn main []
  (reagent/create-class
    {:component-did-mount attach-body-events
     :reagent-render
       (fn []
         (let [bank       (subscribe [:idresolver/bank])
               no-matches (subscribe [:idresolver/results-no-matches])
               result-count (- (count @bank) (count @no-matches))]
         [:div.container.idresolverupload
          [:div.headerwithguidance
           [:h1 "List Upload"]
           [:a.guidance {:on-click (fn [] (dispatch [:idresolver/resolve (splitter (ex))]))} "[Show me an example]"]]
           [input-div]
           [stats]
           (cond (> result-count 0) [preview result-count])
        ;[selected]
        ;[debugger]
        ]))}))