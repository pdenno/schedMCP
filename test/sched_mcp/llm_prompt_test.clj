(ns sched-mcp.llm-prompt-test
  "Test LLM prompts for quality and effectiveness
   Week 3 Day 3: Testing and Prompt Refinement"
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [sched-mcp.llm :as llm]
   [sched-mcp.system-db :as sdb]))
