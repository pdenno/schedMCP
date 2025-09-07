(ns sched-mcp.tools.iviewr.discovery-schema
  "So far, this is required by interviewers, and it only brings in more requirements, the EADSs."
  (:require
   ;; These are all for mount
   [sched-mcp.tools.iviewr.domain.data.orm]
   [sched-mcp.tools.iviewr.domain.process.flow_shop]
   [sched-mcp.tools.iviewr.domain.process.job_shop_c]
   [sched-mcp.tools.iviewr.domain.process.job_shop]
   [sched-mcp.tools.iviewr.domain.process.job_shop_u]
   [sched-mcp.tools.iviewr.domain.process.scheduling-problem-type]
   [sched-mcp.tools.iviewr.domain.process.timetabling]
   [sched-mcp.tools.iviewr.domain.process.warm-up-with-challenges]))
