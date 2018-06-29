(ns demand_builder.m4plugin
  (:require [spork.util.excel [core :as ex]]
            [spork.util [io :as io]]
            [spork.util [table :as tbl]]
            [clojure.java [io :as jio]]
            [demand_builder.formatter :as formatter]))

;;Name of outputdir file will be created in (not full path)
(def outputdir "Outputs")

;;Reads input file.
;;Input files should have the fields Path, Type, ForceCode, and Sheetname
;;Path is the full filepath of the file to be included
;;Type is the file type; this can be either MAP, CONSOLIDATED, or FORGE
;;ForceCode is the force code that is used in the MAP file. Only FORGE files need this field, it can be empty for MAP and CONSOLIDATED types
(defn read-input-file [filename & {:keys [schema] :or {schema {:Path :text :Type #(clojure.string/upper-case %) :ForceCode :text :Sheetname :text}}}]
  (as-> filename it (spork.util.table/tabdelimited->records it :schema schema) (into [] it)))

;;Makes new file with content from old file and removes old file
(defn rename-file [old-file new-file]
  (io/fcopy (jio/file old-file) (jio/file new-file))
  (io/delete-file-recursively old-file))

;;Need a way to get the force code from the forge file
;;Gets the ForceCode for the forgefile from the input map field
(defn forge-filename->fc [forgefile input-map]
  (:ForceCode (first (filter #(= forgefile (:Path %)) input-map))))

;;Now using normal tabular input instead of weird SRC_By_Day formatting
(defn forgexlsx->tsv [forgefile dir input-map]
  (let [p (first (filter #(= forgefile (:Path %)) input-map))]
    (ex/xlsx->tabdelimited forgefile :rootdir dir :sheetnames [(:Path p)])
    (rename-file
      (str dir (:Sheetname p) ".txt") 
      (str dir (io/as-directory outputdir) "FORGE_" (forge-filename->fc forgefile input-map) ".txt"))))

(defn forgexlsx->tsv [forgefile dir input-map]
  (let [p (first (filter #(= forgefile (:Path %)) input-map))]
    (ex/xlsx->tabdelimited forgefile :rootdir dir :sheetnames [(:Sheetname p)])
    (rename-file (str dir (:Sheetname p) ".txt") (str dir "FORGE_" (forge-filename->fc forgefile input-map) ".txt"))))

;;Reads the first line of a tab delimited text file
(defn read-header [file]
  (with-open [r (clojure.java.io/reader file)]
    (clojure.string/split (first (line-seq r)) #"\t")))

;;Map to replace header with expected field name
(def header-map 
  {"Event Code" "ForceCode"
   "EventCode" "ForceCode"
   "Force List Code (ID)" "ForceCode"
   "Start" "StartDay"
   "SRC" "SRC"
   "SRC TITLE" "Title"
   "SRC TITLE`" "Title"
   "STR" "Strength"
   "QTY" "Quantity"
   "Title 10_32" "Title10_32"})
  
;;Uses header-map to replace the header with expected column names
(defn resolve-header [header]
  (for [h header :let [replacement (get header-map h)]]
    (if replacement replacement h)))

;;Will replace header of text file created from exel file with expected names for columns (using header map)
;;To add an additional case where names may be different, just add the given column name to header-map with key of expected value
(defn fix-header [file]
  (let [header (read-header file)
        fixed-header (resolve-header header)
        newfile (str file "-temp")
        new-header-line (str (apply str (map #(str % "\t") fixed-header)) "\n")]
    (do
      (spit newfile new-header-line)
      (with-open [r (clojure.java.io/reader file)]
        (let [lines (drop 1 (line-seq r))]
          (doseq [line lines]
            (spit newfile (str line "\n") :append true))))
      (rename-file newfile file))))


;;Returns filepath of MAP file (only takes first one if multiple)
(defn find-map-file [input-map]
  (:Path (first (filter #(= "MAP" (:Type %)) input-map))))

;;Converts the excel workbooks to tsv and creates the required path structure and file names
(defn setup-directory [input-file]
  (let [root (io/as-directory (clojure.string/replace input-file (io/fname input-file) ""))
        inputs (io/as-directory (str root outputdir))
        in-map (read-input-file input-file)
        find-file (fn [type] (filter #(= type (:Type %)) in-map))
        vmap (first (find-file "MAP"))
        vcons (first (find-file "CONSOLIDATED"))
        forges (map :Path (find-file "FORGE"))
        _ (io/make-folders! inputs)
        _ (ex/xlsx->tabdelimited (:Path vmap) :rootdir inputs)
        _ (ex/xlsx->tabdelimited (:Path vcons) :rootdir inputs)
        _ (doseq [f forges] (forgexlsx->tsv f (io/as-directory (str root outputdir)) in-map))
        new-map (str (io/as-directory (str root outputdir)) (:Sheetname vmap) ".txt")
        new-con (str (io/as-directory (str root outputdir)) (:Sheetname vcons) ".txt")]
    (fix-header new-map)
    (fix-header new-con)
    (rename-file new-map (clojure.string/replace new-map (io/fname new-map) (str "MAP_" (io/fname new-map))))
    (rename-file new-con (clojure.string/replace new-con (io/fname new-con) (str "CONSOLIDATED_" (io/fname new-con))))))

;;Builds demand file by formatting inputs according to the input-file
(defn inputfile->demand [input-file]
  (let [_ (setup-directory input-file)
        root (io/as-directory (str (clojure.string/replace input-file (io/fname input-file) "") outputdir))]
    (formatter/root->demandfile root)))

