(ns markdown.runner
  (:require [doo.runner :refer-macros [doo-tests]]
            [markdown.md-test]))

(doo-tests 'markdown.md-test)
