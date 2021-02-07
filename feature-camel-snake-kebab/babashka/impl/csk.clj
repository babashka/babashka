(ns babashka.impl.csk
  {:no-doc true}
  (:require [camel-snake-kebab.core :as csk]
            [sci.core :as sci]))

(def cns (sci/create-ns 'camel.snake.kabe nil))

#_(do (require '[camel-snake-kebab.core])
    (doseq [k (sort (keys (ns-publics 'camel-snake-kebab.core)))]
      (println (str "'" k) (format "(sci/copy-var csk/%s cns)" k))))

(def csk-namespace
  {'->Camel_Snake_Case (sci/copy-var csk/->Camel_Snake_Case cns)
   '->Camel_Snake_Case_Keyword (sci/copy-var csk/->Camel_Snake_Case_Keyword cns)
   '->Camel_Snake_Case_String (sci/copy-var csk/->Camel_Snake_Case_String cns)
   '->Camel_Snake_Case_Symbol (sci/copy-var csk/->Camel_Snake_Case_Symbol cns)
   '->HTTP-Header-Case (sci/copy-var csk/->HTTP-Header-Case cns)
   '->HTTP-Header-Case-Keyword (sci/copy-var csk/->HTTP-Header-Case-Keyword cns)
   '->HTTP-Header-Case-String (sci/copy-var csk/->HTTP-Header-Case-String cns)
   '->HTTP-Header-Case-Symbol (sci/copy-var csk/->HTTP-Header-Case-Symbol cns)
   '->PascalCase (sci/copy-var csk/->PascalCase cns)
   '->PascalCaseKeyword (sci/copy-var csk/->PascalCaseKeyword cns)
   '->PascalCaseString (sci/copy-var csk/->PascalCaseString cns)
   '->PascalCaseSymbol (sci/copy-var csk/->PascalCaseSymbol cns)
   '->SCREAMING_SNAKE_CASE (sci/copy-var csk/->SCREAMING_SNAKE_CASE cns)
   '->SCREAMING_SNAKE_CASE_KEYWORD (sci/copy-var csk/->SCREAMING_SNAKE_CASE_KEYWORD cns)
   '->SCREAMING_SNAKE_CASE_STRING (sci/copy-var csk/->SCREAMING_SNAKE_CASE_STRING cns)
   '->SCREAMING_SNAKE_CASE_SYMBOL (sci/copy-var csk/->SCREAMING_SNAKE_CASE_SYMBOL cns)
   '->camelCase (sci/copy-var csk/->camelCase cns)
   '->camelCaseKeyword (sci/copy-var csk/->camelCaseKeyword cns)
   '->camelCaseString (sci/copy-var csk/->camelCaseString cns)
   '->camelCaseSymbol (sci/copy-var csk/->camelCaseSymbol cns)
   '->kebab-case (sci/copy-var csk/->kebab-case cns)
   '->kebab-case-keyword (sci/copy-var csk/->kebab-case-keyword cns)
   '->kebab-case-string (sci/copy-var csk/->kebab-case-string cns)
   '->kebab-case-symbol (sci/copy-var csk/->kebab-case-symbol cns)
   '->snake_case (sci/copy-var csk/->snake_case cns)
   '->snake_case_keyword (sci/copy-var csk/->snake_case_keyword cns)
   '->snake_case_string (sci/copy-var csk/->snake_case_string cns)
   '->snake_case_symbol (sci/copy-var csk/->snake_case_symbol cns)
   'convert-case (sci/copy-var csk/convert-case cns)})
