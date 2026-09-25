# Parallel Construction Projects Implementation Plan

> **For agentic workers:** Use the Superpowers test-first and verification workflows for each step.

**Goal:** Allow several two-block projects to run at once with different goblins sharing physical warehouse stock.

**Architecture:** Replace the single persisted plan with a bounded list of up to eight active plans. Each plan remains the owner of its worker, dropped material, progress, and cancellation. The coordinator visits each plan once per second; all stock reads and removals still use real containers. An old `plan` field is accepted on load and written back as the new `plans` list.

**Tech Stack:** Minecraft 1.21.11, Fabric Loader 0.19.2, Java 21, Gradle 9.2.1.

**Spec:** `goblin-settlement-plan/TECH_DESIGN.md` section 7, stage 3; `GAME_DESIGN.md` section 6.

## Global Constraints

- Stay within `goblin-settlement-mod` and its project-only test worlds.
- Preserve physical inventory, world permissions, inactive-chunk pause, and old saved data.
- Do not create virtual stock or force-load chunks in production code.
- Append history only to `goblin-settlement-plan/UpdateLog.md` and refresh `CURRENT_STATUS.md` after verification.

## Task 1: Persist multiple plans

- [x] Add saved-data checks for two independent plans, overlap rejection, worker ownership, cancellation isolation, reload, and migration from old `plan` data.
- [x] Replace the one-plan field with a bounded plan list and targeted plan operations.
- [x] Run the saved-data check as part of the full offline build.

## Task 2: Dispatch and control each plan

- [x] Make the coordinator visit every active plan, reserve each worker for only one plan, and preserve death recovery per plan.
- [x] Make `plan`, `project`, and `cancel` address the correct project; require an origin when cancellation is ambiguous.
- [x] Run the full offline build.

## Task 3: Verify in the dedicated game world

- [x] Create two non-overlapping plans and two residents using one public chest with limited real planks.
- [x] Confirm both projects progress independently, no duplicate material appears, and a shortage pauses only unfinished work.
- [x] Save, stop, restart, replenish, and confirm both projects finish with correct blocks and inventory.
- [x] Remove temporary force-loading and stop the server.
- [ ] Run the final offline build, update project status and append-only history, and push the project repository.
