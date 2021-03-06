(ns jise.emit
  (:require [jise.insns :as insns]
            [jise.parse]
            [jise.type :as t])
  (:import [clojure.asm AnnotationVisitor ClassVisitor ClassWriter Label MethodVisitor Opcodes Type]
           [clojure.lang Compiler DynamicClassLoader]
           [java.lang.annotation RetentionPolicy]
           [jise.parse AnnotationRecord]))

(set! *warn-on-reflection* true)

(defn- make-emitter [mv debug?]
  {:mv mv
   :continue-label nil
   :break-label nil
   :labels {}
   :debug? debug?})

(defn access-value [flags]
  (let [attrs {:abstract Opcodes/ACC_ABSTRACT
               :final Opcodes/ACC_FINAL
               :private Opcodes/ACC_PRIVATE
               :protected Opcodes/ACC_PROTECTED
               :public Opcodes/ACC_PUBLIC
               :static Opcodes/ACC_STATIC
               :synchronized Opcodes/ACC_SYNCHRONIZED
               :transient Opcodes/ACC_TRANSIENT
               :varargs Opcodes/ACC_VARARGS
               :volatile Opcodes/ACC_VOLATILE}]
    (apply + (keep attrs flags))))

(defn- emit-annotation [^AnnotationVisitor av values]
  (doseq [[name value] values]
    (cond (vector? value)
          (let [av' (.visitArray av name)]
            (doseq [v value]
              (emit-annotation av' v))
            (.visitEnd av'))

          (instance? AnnotationRecord value)
          (let [av' (.visitAnnotation av name (.getDescriptor ^Type (:type value)))]
            (emit-annotation av' (:values value))
            (.visitEnd av'))

          :else (.visit av name value))))

(defn emit-annotations [visitor-fn annotations]
  (doseq [{:keys [retention values] :as ann} annotations
          :when (not= retention RetentionPolicy/SOURCE)]
    (let [^AnnotationVisitor av (visitor-fn ann)]
      (emit-annotation av values)
      (.visitEnd av))))

(defn- emit-field [^ClassWriter cw {:keys [access name annotations type value]}]
  (let [access (access-value access)
        desc (.getDescriptor ^Type type)
        value' (when value
                 ((get {t/BYTE byte t/SHORT short t/INT int
                        t/LONG long t/FLOAT float t/DOUBLE double}
                       type
                       identity)
                  value))
        fv (.visitField cw access (munge name) desc nil value')]
    (emit-annotations (fn [{:keys [^Type type retention]}]
                        (.visitAnnotation fv (.getDescriptor type)
                                          (= retention RetentionPolicy/RUNTIME)))
                      annotations)
    (.visitEnd fv)))

(defmulti emit-expr* (fn [emitter expr] (:op expr)))
(defmethod emit-expr* :default [_ expr]
  (throw (ex-info (str "unknown expr found: " expr) {:expr expr})))

(defn- emit-line [{:keys [^MethodVisitor mv]} line]
  (when line
    (let [here (Label.)]
      (.visitLabel mv here)
      (.visitLineNumber mv line here))))

(defn- emit-return [{:keys [^MethodVisitor mv]} ^Type type]
  (.visitInsn mv (.getOpcode type Opcodes/IRETURN)))

(defn emit-expr [{:keys [^MethodVisitor mv] :as emitter} {:keys [context] :as expr}]
  (emit-expr* emitter expr)
  (when (:return context)
    (let [t (if (:expression context)
              (or (:type expr) t/OBJECT)
              t/VOID)]
      (emit-return emitter t))))

(defn- emit-ctor-invocation
  [{:keys [^MethodVisitor mv] :as emitter} {:keys [ctor args line]}]
  (let [{:keys [class param-types]} ctor
        method-type (Type/getMethodType t/VOID (into-array Type param-types))
        iname (.getInternalName ^Type class)
        desc (.getDescriptor ^Type method-type)]
    (doseq [arg args]
      (emit-expr emitter arg))
    (emit-line emitter line)
    (.visitMethodInsn mv Opcodes/INVOKESPECIAL iname "<init>" desc false)))

(defn- emit-local-name [emitter {:keys [name ^Type type index]} start-label end-label]
  (when (:debug? emitter)
    (.visitLocalVariable ^MethodVisitor (:mv emitter) name (.getDescriptor type) nil
                         start-label end-label index)))

(defn- emit-method
  [^ClassWriter cw parent debug?
   {:keys [name annotations access return-type exceptions args body static-initializer? ctor? varargs?]}]
  (let [desc (->> (map :type args)
                  (into-array Type)
                  (Type/getMethodDescriptor return-type))
        mname (cond static-initializer? "<clinit>"
                    ctor? "<init>"
                    :else (munge name))
        excs (some->> (seq exceptions)
                      (map #(.getInternalName ^Type %))
                      (into-array String))
        mv (.visitMethod cw (access-value (cond-> access varargs? (conj :varargs))) mname desc nil excs)
        start-label (Label.)
        end-label (Label.)
        emitter (make-emitter mv debug?)]
    (emit-annotations (fn [{:keys [^Type type retention]}]
                        (.visitAnnotation mv (.getDescriptor type) (= retention RetentionPolicy/RUNTIME)))
                      annotations)
    (doseq [[i {:keys [name access annotations]}] (map-indexed vector args)]
      (.visitParameter mv name (access-value access))
      (emit-annotations (fn [{:keys [^Type type retention]}]
                          (.visitParameterAnnotation mv i (.getDescriptor type)
                                                     (= retention RetentionPolicy/RUNTIME)))
                        annotations))
    (.visitCode mv)
    (.visitLabel mv start-label)
    (when-not (:abstract access)
      (emit-expr emitter body))
    (.visitLabel mv end-label)
    (doseq [arg args]
      (emit-local-name emitter arg start-label end-label))
    (.visitMaxs mv 1 1)
    (.visitEnd mv)))

(defn emit-class
  [{:keys [source name annotations access parent interfaces static-initializer ctors fields methods]}]
  (let [cw (ClassWriter. ClassWriter/COMPUTE_FRAMES)
        debug? (true? (some-> (System/getProperty "jise.debug") read-string))]
    (.visit cw Opcodes/V1_8
            (+ (access-value access) Opcodes/ACC_SUPER)
            name
            nil
            (.getInternalName ^Type parent)
            (into-array String (map #(.getInternalName ^Type %) interfaces)))
    (emit-annotations (fn [{:keys [^Type type retention]}]
                        (.visitAnnotation cw (.getDescriptor type) (= retention RetentionPolicy/RUNTIME)))
                      annotations)
    (when source
      (.visitSource cw source nil))
    (doseq [field fields]
      (emit-field cw field))
    (when static-initializer
      (emit-method cw parent debug? static-initializer))
    (doseq [ctor ctors]
      (emit-method cw parent debug? ctor))
    (doseq [method methods]
      (emit-method cw parent debug? method))
    (.visitEnd cw)
    (.toByteArray cw)))

(defmethod emit-expr* :do [emitter {:keys [exprs]}]
  (doseq [expr exprs]
    (emit-expr emitter expr)))

(defn- drop-if-statement [{:keys [^MethodVisitor mv]} context]
  (when (:statement context)
    (let [opcode (if (= (t/type-category type) 2)
                   Opcodes/POP2
                   Opcodes/POP)]
      (.visitInsn mv opcode))))

(defn- push-null-unless-statement [{:keys [^MethodVisitor mv]} context]
  (when-not (:statement context)
    (.visitInsn mv Opcodes/ACONST_NULL)))

(defmethod emit-expr* :null [emitter {:keys [context]}]
  (push-null-unless-statement emitter context))

(defn- primitive-type [type]
  (if (#{t/BYTE t/CHAR t/SHORT} type) t/INT type))

(defmethod emit-expr* :literal [{:keys [^MethodVisitor mv]} {:keys [type value context]}]
  (when-not (:statement context)
    (let [v (condp = type
              t/BYTE (unchecked-byte value)
              t/SHORT (unchecked-short value)
              t/CHAR (unchecked-int value)
              t/INT (unchecked-int value)
              t/LONG (unchecked-long value)
              t/FLOAT (unchecked-float value)
              t/DOUBLE (unchecked-double value)
              value)]
      (if-let [opcode (get-in insns/const-insns [(primitive-type type) v])]
        (.visitInsn mv opcode)
        (cond (and (#{t/BYTE t/SHORT t/CHAR t/INT} type)
                   (<= Byte/MIN_VALUE v Byte/MAX_VALUE))
              (.visitIntInsn mv Opcodes/BIPUSH v)

              (and (#{t/SHORT t/INT} type)
                   (<= Short/MIN_VALUE v Short/MAX_VALUE))
              (.visitIntInsn mv Opcodes/SIPUSH v)

              (and (= type t/CLASS) (t/primitive-type? value))
              (let [owner (.getInternalName ^Type (t/boxed-types value))
                    desc (.getDescriptor ^Type t/CLASS)]
                (.visitFieldInsn mv Opcodes/GETSTATIC owner "TYPE" desc))

              :else (.visitLdcInsn mv v))))))

(defn- emit-load [{:keys [^MethodVisitor mv]} ^Type type index]
  (.visitVarInsn mv (.getOpcode type Opcodes/ILOAD) index))

(defmethod emit-expr* :local [emitter {:keys [type local context]}]
  (when-not (:statement context)
    (emit-load emitter type (:index local))))

(defmethod emit-expr* :super [emitter {:keys [type context]}]
  (when-not (:statement context)
    (emit-load emitter type 0)))

(defn- emit-arithmetic [{:keys [^MethodVisitor mv] :as emitter} {:keys [type lhs rhs context line]} op]
  (let [opcode (.getOpcode ^Type type (get insns/arithmetic-insns op))]
    (emit-expr emitter lhs)
    (emit-expr emitter rhs)
    (emit-line emitter line)
    (.visitInsn mv opcode)
    (drop-if-statement emitter context)))

(defmethod emit-expr* :add [emitter expr]
  (emit-arithmetic emitter expr :add))

(defmethod emit-expr* :sub [emitter expr]
  (emit-arithmetic emitter expr :sub))

(defmethod emit-expr* :mul [emitter expr]
  (emit-arithmetic emitter expr :mul))

(defmethod emit-expr* :div [emitter expr]
  (emit-arithmetic emitter expr :div))

(defmethod emit-expr* :rem [emitter expr]
  (emit-arithmetic emitter expr :rem))

(defmethod emit-expr* :neg [{:keys [^MethodVisitor mv] :as emitter} {:keys [type operand context line]}]
  (emit-expr emitter operand)
  (emit-line emitter line)
  (.visitInsn mv (.getOpcode ^Type type Opcodes/INEG))
  (drop-if-statement emitter context))

(defmethod emit-expr* :bitwise-and [emitter expr]
  (emit-arithmetic emitter expr :bitwise-and))

(defmethod emit-expr* :bitwise-or [emitter expr]
  (emit-arithmetic emitter expr :bitwise-or))

(defmethod emit-expr* :bitwise-xor [emitter expr]
  (emit-arithmetic emitter expr :bitwise-xor))

(defmethod emit-expr* :shift-left [emitter expr]
  (emit-arithmetic emitter expr :shift-left))

(defmethod emit-expr* :shift-right [emitter expr]
  (emit-arithmetic emitter expr :shift-right))

(defmethod emit-expr* :logical-shift-right [emitter expr]
  (emit-arithmetic emitter expr :logical-shift-right))

(defmethod emit-expr* :widening-primitive [{:keys [^MethodVisitor mv] :as emitter} {:keys [type src context]}]
  (if (and (= (:op src) :literal) (#{t/LONG t/DOUBLE} type))
    (emit-expr emitter (assoc src :context context :type type))
    (do (emit-expr emitter src)
        (when-let [opcode (get-in insns/widening-insns [(:type src) type])]
          (.visitInsn mv opcode))
        (drop-if-statement emitter context))))

(defmethod emit-expr* :narrowing-primitive [{:keys [^MethodVisitor mv] :as emitter} {:keys [type src context]}]
  (if (and (= (:op src) :literal) (#{t/BYTE t/SHORT t/CHAR t/FLOAT} type))
    (emit-expr emitter (assoc src :context context :type type))
    (do (emit-expr emitter src)
        (case type
          (byte char short)
          (do (when-let [opcode (get-in insns/narrowing-insns [(:type src) t/INT])]
                (.visitInsn mv opcode))
              (.visitInsn mv (get-in insns/narrowing-insns [t/INT type])))
          (.visitInsn mv (get-in insns/narrowing-insns [(:type src) type])))
        (drop-if-statement emitter context))))

(defmethod emit-expr* :boxing [emitter {:keys [type src context]}]
  (emit-expr emitter {:op :method-invocation
                      :context context
                      :type type
                      :method {:class type
                               :access #{:public :static}
                               :name "valueOf"
                               :param-types [(:type src)]}
                      :args [src]}))

(def ^:private unboxing-method-names
  {t/BOOLEAN "booleanValue"
   t/BYTE "byteValue"
   t/CHAR "charValue"
   t/SHORT "shortValue"
   t/INT "intValue"
   t/LONG "longValue"
   t/FLOAT "floatValue"
   t/DOUBLE "doubleValue"})

(defmethod emit-expr* :unboxing [emitter {:keys [type src context]}]
  (emit-expr emitter {:op :method-invocation
                      :context context
                      :type type
                      :method {:class (:type src)
                               :access #{:public}
                               :name (unboxing-method-names type)
                               :param-types []}
                      :target src
                      :args []}))

(defmethod emit-expr* :widening-reference [emitter {:keys [src]}]
  (emit-expr emitter src))

(defmethod emit-expr* :narrowing-reference [{:keys [^MethodVisitor mv] :as emitter} {:keys [type src context]}]
  (emit-expr emitter src)
  (.visitTypeInsn mv Opcodes/CHECKCAST (.getInternalName ^Type type))
  (drop-if-statement emitter context))

(defmethod emit-expr* :instance? [{:keys [^MethodVisitor mv] :as emitter} {:keys [class operand context line]}]
  (emit-expr emitter operand)
  (emit-line emitter line)
  (.visitTypeInsn mv Opcodes/INSTANCEOF (.getInternalName ^Type class))
  (drop-if-statement emitter context))

(defn- emit-store [{:keys [^MethodVisitor mv]} ^Type type index]
  (.visitVarInsn mv (.getOpcode type Opcodes/ISTORE) index))

(defmethod emit-expr* :let [{:keys [^MethodVisitor mv] :as emitter} {:keys [bindings body line]}]
  (let [start-labels (map (fn [_] (Label.)) bindings)
        end-label (Label.)]
    (emit-line emitter line)
    (doseq [[{:keys [init] :as b} start-label] (map vector bindings start-labels)]
      (emit-expr emitter init)
      (emit-store emitter (:type b) (:index b))
      (.visitLabel mv start-label))
    (emit-expr emitter body)
    (.visitLabel mv end-label)
    (doseq [[binding start-label] (map vector bindings start-labels)]
      (emit-local-name emitter binding start-label end-label))))

(defn- emit-dup [{:keys [^MethodVisitor mv]} type]
  (let [opcode (case (t/type-category type)
                 1 Opcodes/DUP
                 2 Opcodes/DUP2)]
    (.visitInsn mv opcode)))

(defn- dup-unless-statement [emitter context type]
  (when-not (:statement context)
    (emit-dup emitter type)))

(defmethod emit-expr* :assignment [emitter {:keys [lhs rhs context line]}]
  (emit-expr emitter rhs)
  (dup-unless-statement emitter context (:type rhs))
  (emit-line emitter line)
  (emit-store emitter (:type lhs) (:index (:local lhs))))

(defmethod emit-expr* :increment [{:keys [^MethodVisitor mv] :as emitter} {:keys [target by context line]}]
  (let [{:keys [type local]} target]
    (emit-line emitter line)
    (.visitIincInsn mv (:index local) by)
    (when-not (:statement context)
      (emit-load emitter type (:index local)))))

(defmethod emit-expr* :labeled [{:keys [^MethodVisitor mv] :as emitter} {:keys [label target kind]}]
  (let [break-label (Label.)
        emitter' (assoc-in emitter [:labels label] {:break-label break-label})]
    (emit-expr emitter' target)
    (.visitLabel mv break-label)))

(defn- emit-comparison [{:keys [^MethodVisitor mv] :as emitter} op {:keys [operand lhs rhs]} label]
  (if operand
    (let [opcode (get insns/constant-comparison-insns op)]
      (emit-expr emitter operand)
      (.visitJumpInsn mv opcode label))
    (let [t (:type lhs)]
      (emit-expr emitter lhs)
      (emit-expr emitter rhs)
      (if-let [[opcode1 opcode2] (get-in insns/comparison-insns [t op])]
        (if opcode2
          (do (.visitInsn mv opcode1)
              (.visitJumpInsn mv opcode2 label))
          (.visitJumpInsn mv opcode1 label))
        (let [opcode (case op
                       :eq Opcodes/IF_ACMPNE
                       :ne Opcodes/IF_ACMPEQ)]
          (.visitJumpInsn mv opcode label))))))

(declare emit-conditional)

(defn- emit-and [emitter {:keys [exprs]} label]
  (run! #(emit-conditional emitter % label) exprs))

(defn- emit-or [{:keys [^MethodVisitor mv] :as emitter} {:keys [exprs expr]} else-label]
  (let [then-label (Label.)]
    (run! #(emit-conditional emitter % then-label) exprs)
    (emit-conditional emitter expr else-label)
    (.visitLabel mv then-label)))

(def negated-comparison-ops
  {:eq :ne, :ne :eq, :lt :ge, :gt :le, :le :gt, :ge :lt
   :eq-0 :ne-0, :ne-0 :eq-0, :eq-null :ne-null, :ne-null :eq-null
   :lt-0 :ge-0, :gt-0 :le-0, :le-0 :gt-0, :ge-0 :lt-0})

(defn- emit-not [{:keys [^MethodVisitor mv] :as emitter} {:keys [expr]} label]
  (if-let [negated (negated-comparison-ops (:op expr))]
    (emit-comparison emitter negated expr label)
    (do (emit-expr emitter expr)
        (.visitJumpInsn mv Opcodes/IFNE label))))

(defn- emit-conditional [{:keys [^MethodVisitor mv] :as emitter} cond label]
  (let [op (:op cond)]
    (case op
      (:eq :ne :lt :gt :le :ge :eq-null :ne-null :eq-0 :ne-0 :lt-0 :gt-0 :le-0 :ge-0)
      (emit-comparison emitter op cond label)
      :and
      (emit-and emitter cond label)
      :or
      (emit-or emitter cond label)
      :not
      (emit-not emitter cond label)

      (do (emit-expr emitter cond)
          (.visitJumpInsn mv Opcodes/IFEQ label)))))

(defmethod emit-expr* :if [{:keys [^MethodVisitor mv] :as emitter} {:keys [test then else line]}]
  (let [end-label (Label.)
        else-label (if else (Label.) end-label)]
    (emit-line emitter line)
    (emit-conditional emitter test else-label)
    (emit-expr emitter then)
    (when else
      (when-not (:tail (:context then))
        (.visitJumpInsn mv Opcodes/GOTO end-label))
      (.visitLabel mv else-label)
      (emit-expr emitter else))
    (.visitLabel mv end-label)))

(defn- assign-labels [clauses]
  (loop [clauses clauses
         key->label {}
         ret []]
    (if (empty? clauses)
      [ret (sort-by first key->label)]
      (let [[{:keys [keys] :as clause} & clauses] clauses
            label (or (some key->label keys) (Label.))]
        (recur clauses
               (into key->label (map (fn [k] [k label])) keys)
               (conj ret (assoc clause :label label)))))))

(defn- sequential-min-max-keys [keys]
  (let [keys' (into (sorted-set) keys)]
    (when (->> keys'
               (partition 2 1)
               (every? (fn [[k k']] (= (inc k) k'))))
      [(first (seq keys'))
       (first (rseq keys'))])))

(defmethod emit-expr* :switch
  [{:keys [^MethodVisitor mv] :as emitter} {:keys [test clauses default]}]
  (let [end-label (Label.)
        default-label (if default (Label.) end-label)
        [clauses' key->label] (assign-labels clauses)
        keys (int-array (map first key->label))
        labels (into-array Label (map second key->label))]
    (emit-expr emitter test)
    (if-let [[min max] (sequential-min-max-keys keys)]
      (->> (sort-by key key->label)
           (map val)
           (into-array Label)
           (.visitTableSwitchInsn mv min max default-label))
      (.visitLookupSwitchInsn mv default-label keys labels))
    (doseq [{:keys [label guard body]} clauses']
      (.visitLabel mv label)
      (when guard
        (emit-conditional emitter guard default-label))
      (emit-expr emitter body)
      (when-not (:tail (:context body))
        (.visitJumpInsn mv Opcodes/GOTO end-label)))
    (when default
      (.visitLabel mv default-label)
      (emit-expr emitter default))
    (.visitLabel mv end-label)))

(defn- with-labels [emitter label-name continue-label break-label f]
  (let [emitter' (-> emitter
                     (assoc :continue-label continue-label :break-label break-label)
                     (cond-> label-name (assoc-in [:labels label-name :continue-label] continue-label)))]
    (f emitter')))

(defmethod emit-expr* :while [{:keys [^MethodVisitor mv] :as emitter} {:keys [cond body label context line]}]
  (let [start-label (Label.)
        end-label (Label.)]
    (with-labels emitter label start-label end-label
      (fn [emitter']
        (.visitLabel mv start-label)
        (emit-line emitter' line)
        (when-not (and (= (:op cond) :literal) (true? (:value cond)))
          (emit-conditional emitter' cond end-label))
        (emit-expr emitter' body)
        (.visitJumpInsn mv Opcodes/GOTO start-label)
        (.visitLabel mv end-label)))
    (push-null-unless-statement emitter context)))

(defmethod emit-expr* :for [{:keys [^MethodVisitor mv] :as emitter} {:keys [cond step body label context]}]
  (let [start-label (Label.)
        continue-label (Label.)
        end-label (Label.)]
    (with-labels emitter label continue-label end-label
      (fn [emitter']
        (.visitLabel mv start-label)
        (when-not (and (= (:op cond) :literal) (true? (:value cond)))
          (emit-conditional emitter' cond end-label))
        (emit-expr emitter' body)
        (.visitLabel mv continue-label)
        (emit-expr emitter' step)
        (.visitJumpInsn mv Opcodes/GOTO start-label)
        (.visitLabel mv end-label)))
    (push-null-unless-statement emitter context)))

(defmethod emit-expr* :try
  [{:keys [^MethodVisitor mv] :as emitter} {:keys [type body catch-clauses finally-clause]}]
  (let [body-start-label (Label.)
        body-end-label (Label.)
        try-end-label (Label.)
        default-clause-label (when finally-clause (Label.))
        catch-clauses' (map #(assoc % :start-label (Label.) :end-label (Label.)) catch-clauses)]
    (.visitLabel mv body-start-label)
    (emit-expr emitter body)
    (.visitLabel mv body-end-label)
    (when finally-clause
      (emit-expr emitter finally-clause))
    (.visitJumpInsn mv Opcodes/GOTO try-end-label)
    (doseq [[{:keys [start-label end-label local body]} more] (partition-all 2 1 catch-clauses')
            :let [label (Label.)]]
      (.visitLabel mv start-label)
      (emit-store emitter (or (:type local) t/THROWABLE) (:index local))
      (.visitLabel mv label)
      (emit-expr emitter body)
      (.visitLabel mv end-label)
      (emit-local-name emitter local label end-label)
      (when finally-clause
        (emit-expr emitter finally-clause))
      (when (or finally-clause more)
        (.visitJumpInsn mv Opcodes/GOTO try-end-label)))
    (when finally-clause
      (.visitLabel mv default-clause-label)
      (emit-expr emitter finally-clause)
      (.visitInsn mv Opcodes/ATHROW))
    (.visitLabel mv try-end-label)
    (doseq [{:keys [start-label local]} catch-clauses'
            :let [iname (.getInternalName ^Type (:type local))]]
      (.visitTryCatchBlock mv body-start-label body-end-label start-label iname))
    (when finally-clause
      (.visitTryCatchBlock mv body-start-label body-end-label default-clause-label nil)
      (doseq [{:keys [start-label end-label]} catch-clauses']
        (.visitTryCatchBlock mv start-label end-label default-clause-label nil)))))

(defmethod emit-expr* :continue [{:keys [^MethodVisitor mv] :as emitter} {:keys [label]}]
  (let [^Label label (if label
                       (get-in emitter [:labels label :continue-label])
                       (:continue-label emitter))]
    (.visitJumpInsn mv Opcodes/GOTO label)))

(defmethod emit-expr* :break [{:keys [^MethodVisitor mv] :as emitter} {:keys [label]}]
  (let [^Label label (if label
                       (get-in emitter [:labels label :break-label])
                       (:break-label emitter))]
    (.visitJumpInsn mv Opcodes/GOTO label)))

(defmethod emit-expr* :return [emitter {:keys [type value]}]
  (when-not (= type t/VOID)
    (emit-expr emitter value)))

(defmethod emit-expr* :throw [{:keys [^MethodVisitor mv] :as emitter} {:keys [exception]}]
  (emit-expr emitter exception)
  (.visitInsn mv Opcodes/ATHROW))

(defmethod emit-expr* :new [{:keys [^MethodVisitor mv] :as emitter} {:keys [type context] :as expr}]
  (.visitTypeInsn mv Opcodes/NEW (.getInternalName ^Type type))
  (dup-unless-statement emitter context type)
  (emit-ctor-invocation emitter expr))

(defmethod emit-expr* :field-access
  [{:keys [^MethodVisitor mv] :as emitter} {:keys [type field target context line]}]
  (let [static? (:static (:access field))
        opcode (if static? Opcodes/GETSTATIC Opcodes/GETFIELD)
        owner (.getInternalName ^Type (:class field))
        desc (.getDescriptor ^Type type)]
    (when-not static?
      (emit-expr emitter target))
    (emit-line emitter line)
    (.visitFieldInsn mv opcode owner (munge (:name field)) desc)
    (drop-if-statement emitter context)))

(defmethod emit-expr* :field-update
  [{:keys [^MethodVisitor mv] :as emitter} {:keys [type field target rhs context line]}]
  (let [static? (:static (:access field))
        opcode (if static? Opcodes/PUTSTATIC Opcodes/PUTFIELD)
        owner (.getInternalName ^Type (:class field))
        desc (.getDescriptor ^Type type)]
    (when-not static?
      (emit-expr emitter target))
    (emit-expr emitter rhs)
    (when-not (:statement context)
      (let [t (:type rhs)]
        (if static?
          (dup-unless-statement emitter context t)
          (let [opcode (if (= (t/type-category t) 2) Opcodes/DUP_X2 Opcodes/DUP_X1)]
            (.visitInsn mv opcode)))))
    (emit-line emitter line)
    (.visitFieldInsn mv opcode owner (munge (:name field)) desc)))

(defmethod emit-expr* :ctor-invocation [emitter {:keys [ctor] :as expr}]
  (emit-load emitter (:class ctor) 0)
  (emit-ctor-invocation emitter expr))

(defmethod emit-expr* :method-invocation
  [{:keys [^MethodVisitor mv] :as emitter}
   {:keys [type method super? target args context line]}]
  (let [{:keys [interface? class name access param-types]} method
        static? (:static access)
        method-type (Type/getMethodType ^Type type (into-array Type param-types))
        opcode (cond static? Opcodes/INVOKESTATIC
                     interface? Opcodes/INVOKEINTERFACE
                     (or (:private access) super?) Opcodes/INVOKESPECIAL
                     :else Opcodes/INVOKEVIRTUAL)
        iname (.getInternalName ^Type class)
        desc (.getDescriptor method-type)]
    (when-not static?
      (emit-expr emitter target))
    (doseq [arg args]
      (emit-expr emitter arg))
    (emit-line emitter line)
    (.visitMethodInsn mv opcode iname (munge name) desc (boolean interface?))
    (if (= type t/VOID)
      (push-null-unless-statement emitter context)
      (drop-if-statement emitter context))))

(def ^:private primitive-types
  {t/BOOLEAN Opcodes/T_BOOLEAN
   t/BYTE Opcodes/T_BYTE
   t/CHAR Opcodes/T_CHAR
   t/SHORT Opcodes/T_SHORT
   t/INT Opcodes/T_INT
   t/LONG Opcodes/T_LONG
   t/FLOAT Opcodes/T_FLOAT
   t/DOUBLE Opcodes/T_DOUBLE})

(defmethod emit-expr* :new-array
  [{:keys [^MethodVisitor mv] :as emitter} {:keys [type dims elements context line]}]
  (let [dim (count dims)]
    (run! (partial emit-expr emitter) dims)
    (emit-line emitter line)
    (if (> dim 1)
      (.visitMultiANewArrayInsn mv (.getDescriptor ^Type type) dim)
      (let [elem-type (t/element-type type)]
        (if (t/primitive-type? elem-type)
          (let [t (primitive-types elem-type)]
            (.visitIntInsn mv Opcodes/NEWARRAY t))
          (.visitTypeInsn mv Opcodes/ANEWARRAY (.getInternalName elem-type)))
        (when elements
          (doseq [[i elem] (map-indexed vector elements)]
            (emit-dup emitter type)
            (emit-expr emitter {:op :literal :value i :type t/INT :context #{:expression}})
            (emit-expr emitter elem)
            (.visitInsn mv (.getOpcode elem-type Opcodes/IASTORE))))))
    (drop-if-statement emitter context)))

(defmethod emit-expr* :array-length [{:keys [^MethodVisitor mv] :as emitter} {:keys [array context line]}]
  (emit-expr emitter array)
  (emit-line emitter line)
  (.visitInsn mv Opcodes/ARRAYLENGTH)
  (drop-if-statement emitter context))

(defmethod emit-expr* :array-access [{:keys [^MethodVisitor mv] :as emitter} {:keys [array index context line]}]
  (emit-expr emitter array)
  (emit-expr emitter index)
  (emit-line emitter line)
  (let [elem-type (t/element-type (:type array))]
    (.visitInsn mv (.getOpcode elem-type Opcodes/IALOAD))
    (drop-if-statement emitter context)))

(defmethod emit-expr* :array-update
  [{:keys [^MethodVisitor mv] :as emitter} {:keys [array index expr context line]}]
  (let [elem-type (t/element-type (:type array))]
    (emit-expr emitter array)
    (emit-expr emitter index)
    (emit-expr emitter expr)
    (when-not (:statement context)
      (let [opcode (case (t/type-category elem-type)
                     1 Opcodes/DUP_X2
                     2 Opcodes/DUP2_X2)]
        (.visitInsn mv opcode)))
    (emit-line emitter line)
    (.visitInsn mv (.getOpcode elem-type Opcodes/IASTORE))))
