# AI prompt-output fixtures (V3-2.5)

These are **hand-authored to match the current JSON contract** each parser in
`RoadmapAiService`/`VerificationAiService` expects — not literal captures from a live provider
call. `TASKS_v3.md` V3-2.5 asks for "one real response... captured from a live call the founder
already ran," and its own ground rule (repeated across the file) is **no new AI-quota spend**.
No such capture existed anywhere in the repo, and generating one meant a live call this file
explicitly rules out, so these stand in for it.

That doesn't weaken what actually matters here: the property under test is "does the
parsing/mapping code turn this JSON into the expected domain object, and does a fixture with a
missing/renamed field degrade the way the code documents rather than NPE" — both are exercised
against the real parsing code in `RoadmapAiService`/`VerificationAiService`, unchanged. What's
hand-authored is only the input, and it's shaped directly from the field names those classes
already read (`json.get("...")`) plus the prompts in `com.compass.app.ai.prompts` (split from
the former `PromptTemplates` god-file by V3-4.2) that ask a model to produce them.

If a real captured response is ever saved from an actual run, drop it in here and it's a better
fixture than what's here now — nothing about the test structure needs to change.
