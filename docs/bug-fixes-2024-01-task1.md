# Bug Fixes - Pre-Week 1-2 Task 1

## Fixed Issues

### 1. Null Pointer in `get_interview_answers`

**Problem**: The function was directly accessing fields from `eads-data` without checking if it was nil, causing null pointer exceptions when no EADS data existed yet.

**Solution**: Added defensive programming with `get` functions that provide default values:
```clojure
;; Before
{:phase (name (:phase eads-data))
 :complete? (:complete? eads-data)
 :answers (:answers eads-data)}

;; After  
{:phase (name (get eads-data :phase :unknown))
 :complete? (get eads-data :complete? false)
 :answers (get eads-data :answers {})}
```

Also changed the response when no EADS data exists from an error to a valid response with empty data.

### 2. `submit_answer` Not Progressing

**Problem**: When `question_id` was not provided, the function couldn't determine which question to answer, preventing progression through the interview.

**Solution**: Added logic to automatically get the current question when no `question_id` is provided:
```clojure
;; If no question_id provided, get the current question
current-q (when-not question_id
            (warm-up/get-next-question pid cid))
qid (or question_id
        (when current-q (name (:id current-q))))
```

## Testing Notes

Both fixes should be tested by:
1. Starting a new interview
2. Calling `get_interview_answers` before answering any questions (should return empty data, not error)
3. Submitting answers without providing `question_id` (should automatically use current question)
4. Verifying the interview progresses through all questions

## Next Steps

With these bugs fixed, we can proceed to Task 2: Create DS/EADS File Structure