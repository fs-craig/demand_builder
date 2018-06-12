
;;Defines useful operations for ripping tables from excel workbooks, and 
;;piping data do excel workbooks programatically.  Uses Docjure, which in 
;;turn uses the Apache POI libraries to interact with Excel docs.


;;Intended to be used seamlessly with spork.util.table abstract tables,
;;or record sequences.
(ns demand_builder.excel
  (:use [spork.util.excel.docjure])
  (:require [spork.util [table :as tbl]
                        [vector :as v]
                        [io :as io]
                        [string :as s]])
  (:import [org.apache.poi.ss.usermodel Sheet Cell Row DataFormatter]))


;;we want to enable the ability for tables that have
;;a completely empty rowvector, i.e. all nil or blank,
;;to be seen as a table terminator.


(def +blank+ "")
(defn blank? [v]
  (or (nil? v)
      (and (string? v)
           (identical? v +blank+))))


(defn empty-row? [xs]
  (every? blank? xs))


(def ^:dynamic *end-of-table*)
(set! *warn-on-reflection* true)
            
(comment 
; Load a spreadsheet and read the first two columns from the 
; price list sheet:
  (->> (load-workbook "spreadsheet.xlsx")
       (select-sheet "Price List")
       (select-columns {:A :name, :B :price})))


;; Create a spreadsheet and save it
(let [wb (create-workbook "Price List"
                          [["Name" "Price"]
                           ["Foo Widget" 100]
                           ["Bar Widget" 200]])
      sheet (select-sheet "Price List" wb)
      header-row (first (row-seq sheet))]
  (do
    (set-row-style! header-row (create-cell-style! wb {:background :yellow,
                                                       :font {:bold true}}))
    (save-workbook! "spreadsheet.xlsx" wb)))



;(defn row->vec [r]  
;  (vec (map read-cell (into-seq r))))




(defn row->seq
  [^Row r]
  (vec (for [^Cell item (iterator-seq (.iterator r))] item)))


(defn row->indexed-cells [^Row r] 
  (map (fn [^Cell c] 
         (vector (.getColumnIndex c) (read-cell c))) 
       (iterator-seq (.iterator r))))


;;TODO: revisit this, we can probably do mo betta.
;;causing problems here...
;;Now we allow a custom function for cell->val to be passed in.
;;Note: bound may not be necessary...
;;Since rows are sparse, we're trying to fill in empties that
;;we find.
(defn row->vec
  ([^Row r bound cell->val]
   (let [bounded? (if (not (nil? bound)) 
                    (fn [n] (> n bound))
                    (fn [_] false))
         vs (seq r)]
     (loop [acc []
            idx (int 0)
            xs   vs]
       (cond (empty? xs) acc           
             (bounded? idx) (subvec acc 0 bound)
             :else (let [^Cell x (first xs)                       
                         y       (cell->val x) ;;This is where we'd hook in if we only wanted text.
                         i       (.getColumnIndex x)
                         ;; if i <> idx, we have skipped (i.e. sparse) values
                         missed  (reduce conj acc
                                         (take (- i idx)
                                               (repeat nil)))]
                     (recur (conj missed y) (inc i) (rest xs)))))))
  ([r bound] (row->vec r bound read-cell))
  ([r] (row->vec r nil read-cell)))


(comment
  ;;We use the dataformatter here, just getting strings out.
  (defn row->strings
    ([^Row r ^DataFormatter df]
     (let [bounded? (if (not (nil? bound)) 
                      (fn [n] (> n bound))
                      (fn [_] false))
           vs (seq r)]
       (loop [acc []
              idx (int 0)
              xs   vs]
         (cond (empty? xs) acc           
               (bounded? idx) (subvec acc 0 bound)
               :else (let [^Cell x (first xs)                       
                           y       (.formatCellValue df  x) ;;This is where we'd hook in if we only wanted text.
                           i       (.getColumnIndex x)                        
                           missed  (reduce conj acc
                                           (take (- i idx)
                                                 (repeat nil)))]
                       (recur (conj missed y) (inc i) (rest xs)))))))))



;;Todo: revisit this.  we're eagerly building the rows, we could
;;also stream them.
(defn rows->table
  "Converts an excel worksheet into a columnar table.  Assumes first row defines 
   field names."
  [xs] 
  (when (seq xs)
    (let [rows    (->> xs 
                       (reduce (fn [acc r]
                                 (conj acc (row->vec r))))
                       [])
          fields  (first (subvec rows 0 1))
          records (v/transpose (subvec rows 1))]
      (tbl/make-table fields records))))


;; (defn rows->raw-table
;;   "Converts an excel worksheet into a columnar table.  Assumes first row defines 
;;    field names."
;;   [xs] 
;;   (when (seq xs)
;;     (let [rows    (->> xs 
;;                        (reduce (fn [acc r]
;;                                  (conj acc (row->vec r))))
;;                        [])
;;           fields  (first (subvec rows 0 1))
;;           records (v/transpose (subvec rows 1))]
;;       (tbl/make-table fields records))))   


(defn ucase [^String s] (.toUpperCase s))
(defn lcase [^String s] (.toLowerCase s))
(defn nth-row [idx ^Sheet sheet] (.getRow sheet idx))
(defn first-row [sheet] (nth-row 0 sheet))
(defn truncate-row [v] 
  (let [n (dec (count v))]
    (loop [idx 0]
      (cond (> idx n) v
            (nil? (get v idx)) (subvec v 0 idx)
            :else (recur (inc idx))))))


;(defn contiguous-rows
;  "Fetch a seq of contiguous rows, starting at startrow.  Rows may have
;   noncontiguous cells, however...."
;  [sheet & {:keys [startrow] :or {startrow 0}}]
;  (->> (iterate inc startrow)
;       (map (fn [idx] (nth-row idx sheet)))
;       (take-while #(not (nil? %)))))




(defn contiguous-rows
  "Fetch a seq of contiguous rows, starting at startrow.  Rows may have
   noncontiguous cells, however...."
  [sheet]
  (let [rows (row-seq sheet)
        parts (->> rows       
                (map    (fn [^Row row] [(.getRowNum row) row]))    
                (partition 2 1)
                (filter (fn [[[i1 _] [i2 _]]] (= i1 (dec i2)))))]
    (if (empty? parts)
      rows
      (flatten 
        (concat (map second (first parts)) 
                (map (comp second second) (rest parts)))))))


;;Added optional arguement i that indicates the row index that the tabular section starts
;;Specificly used for FORGE records which need to be formated based on the second row rather than the first
;;Passed optional argument through to high level functions
(defn tabular-region
  "Assumes that sheet represents a table, in which case, the upper-left 
   corner, cell A1, is the beginning of a set of adjacent cells, which form
   a rectangle.  Nil values are allowed in cells in each row, except for the 
   first row, which is assumed to denote field names.  The rectangular region 
   will be truncated after the first nil is found in the field names."
  [sheet & {:keys [i] :or {i 0}}]
  (let [fields (truncate-row (row->vec (nth (contiguous-rows sheet) i)))
        fieldcount (count fields)
        pooled     (s/->string-pool 100 1000)
        read-cell-pooled (fn [cl]
                           (let [res (read-cell cl)]
                             (if (string? res) (pooled res) res)))]
    (->> (contiguous-rows sheet) 
         (map (fn [r]
                (let [r (row->vec r nil read-cell-pooled)
                      rcount (count r)]
                  (cond (= rcount fieldcount) r
                    (> rcount fieldcount) (subvec r 0 fieldcount)
                    (< rcount fieldcount) (into r (take (- fieldcount rcount) 
                                                    (repeat nil)))))))
         (take-while (complement empty-row?))))) ;;we infer a blank row as the end of the table.
         




;;Maybe revisit this....
;;lots of garbage here.  We should be able to directly map the tabular
;;region into corresponding field/rows without creating intermediate
;;vectors...
(defn sheet->table
  "Converts an excel worksheet into a columnar table.  Assumes first row defines 
   field names.  Truncates remaining dataset to the contiguous, non-nil fields 
   in the first row."
  [sheet & {:keys [i] :or {i 0}}] 
  (let [rows    (tabular-region sheet :i i)]
    (when-let [fields  (first rows)]
      (if-let [records (vec (rest rows))]
        (tbl/make-table fields (v/transpose  records))
        (tbl/make-table fields)))))


(defn wb->tables
  "Extract sheets from the workbook located at wbpath, coercing them to tables 
   as per util.table."
  [wb & {:keys [sheetnames i] :or {sheetnames :all i 0}}]
  (let [sheets  (sheet-seq wb)]
    (->> (if (= sheetnames :all) sheets
           (let [names (set (map lcase sheetnames))]
             (filter #(contains? names ((comp lcase sheet-name) %))
                     sheets)))
      (map (fn [s] (do (println (sheet-name s))
                       [(sheet-name s) (sheet->table s :i i)])))
      (into {}))))


(defn tables->workbook
  "Given a map of {tablename0 table0...tablenameN tableN}, renders them 
   to a workbook object, which can be persisted as an xlsx."
  [tablemap]  
  (assert (map? tablemap))
  (let [specs (map (fn [[nm t]]
                     [nm (reduce conj [(tbl/table-fields t)]
                                 (tbl/table-rows t))]) (seq tablemap))
        wb (let [[n data] (first specs)]
             (create-workbook (tbl/field->string n) data))]
    (do (doseq [[n data] (rest specs)]
               (let [sheet (add-sheet! wb (tbl/field->string n))]
                 (add-rows! sheet data)))
      wb)))        


(defn tables->xlsx
  "Given a map of {tablename0 table0...tablenameN tableN}, renders the
   tables as worksheets in a workbook, saving the workbook at path."
  [wbpath tablemap]
  (assert (map? tablemap))
  (save-workbook! wbpath (tables->workbook tablemap)))


(defn table->xlsx
  "Renders table t as a singleton sheet, named sheetname, in an xlsx workbook 
   at wbpath."
  [wbpath sheetname t]
  (tables->xlsx wbpath {sheetname t}))


(defn workbook-dir [wbpath] 
  (-> wbpath 
    (clojure.string/replace  ".xlsx" "\\")
    (clojure.string/replace  ".xlsm" "\\")))


(defn xlsx->tabdelimited 
  "Dumps all the tabular worksheets in an xlsx file into a set of tabdelimited 
   text files.  By default, the text files are dumped in a folder sharing the 
   same name as the original workbook.  Caller can supply a seq of sheetnames 
   and an alternate directory to dump the text files in using :sheetname and 
   :rootdir key arguments."
  [wbpath & {:keys [rootdir sheetnames i] 
             :or {sheetnames :all rootdir (workbook-dir wbpath) i 0}}]
  (let [tmap (wb->tables (load-workbook wbpath) :sheetnames sheetnames :i i)]
    (doseq [[nm t] (seq tmap)]
      (let [textpath (io/relative-path rootdir [(str nm ".txt")])]
        (io/hock textpath (tbl/table->tabdelimited t))))))  


(defn xlsx->tables
  "Extract one or more worksheets from an xls or xlsx workbook as a map of 
   tables, where each sheet is rendered as a contiguous table, with first row 
   equal to field names."
  [wbpath & {:keys [sheetnames ignore-dates? i] 
             :or {sheetnames :all ignore-dates? false i 0}}]
  (if ignore-dates?
    (ignoring-dates
      (wb->tables (load-workbook wbpath) :sheetnames sheetnames :i i))
    (wb->tables (load-workbook wbpath) :sheetnames sheetnames :i i)))
   


(defn xlsx->wb
  "API wrapper for docjure/load-workbook.  Loads an excel workbook from 
   a given workbook path."
  [wbpath] 
  (load-workbook wbpath))


(defmulti as-workbook class)
(defmethod as-workbook java.lang.String [wb] (load-workbook wb))
(defmethod as-workbook org.apache.poi.xssf.usermodel.XSSFWorkbook [wb]
  wb)


(defmethod as-workbook :default [wb] 
  (throw (Exception. (str "Method not implemented for type " (type wb)))))


(defmulti as-sheet (fn [sheet wb] (class sheet)))
(defmethod as-sheet java.lang.String [sheet wb] 
  (select-sheet sheet (as-workbook wb)))


(defmethod as-sheet :default [sheet wb] 
  (throw (Exception. (str "Method not implemented for type " (type sheet)))))


(comment 
  (def wbpath
    "C:\\Users\\thomas.spoon\\Documents\\sampling-utils\\record-rules-large.xlsx")
  
  
  ;testing  
  (def wbpath   
    "C:\\Users\\thomas.spoon\\Documents\\Marathon_NIPR\\OngoingDevelopment\\MPI_3.76029832.xlsm")
  (def outpath "C:\\Users\\thomas.spoon\\Documents\\newWB.xlsx")
  
  
  (def wb (as-workbook wbpath))
  (def tables ["Deployments"
               "DemandTrends" 
               "InScope"
               "OutOfScope"
               "DemandRecords"
               "Parameters"
               "PeriodRecords"
               "RelationRecords"
               "SRCTagRecords"
               "SupplyRecords" 
               "Titles"])
  (def supply (select-sheet "SupplyRecords" wb))
  (def demand (select-sheet "DemandRecords" wb))
  (def tmap (wb->tables wb :sheetnames tables))
  
  
  
  
  (def bigpath 
    "C:\\Users\\thomas.spoon\\Documents\\sampling-utils\\record-rules-large.xlsx"))




