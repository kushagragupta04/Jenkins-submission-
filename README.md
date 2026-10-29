# FestPass: campus event registration + Jenkins

This is a completed **diagnostic starter** for the one-hour Jenkins assessment. It manages
multiple events, seat limits, student registrations, cancellations, and local
file persistence. The seeded booking cancellation defect has been diagnosed and fixed,
all 28 supplied tests pass, and the `Jenkinsfile` is configured with the required
`pollSCM` trigger.

## Project overview

- `FestPass.java`: CLI parsing, output, exit codes, and load/mutate/save flow.
- `BookingService.java`: validation, event management, and booking rules.
- `BookingStore.java`: versioned UTF-8 TSV persistence and atomic file replacement.
- `FestPassTest.java`: unit, persistence, CLI, corruption, and lock tests (28 tests).
- `ci/Build.java`: compile all Java sources with `-Xlint:all -Werror`, test, package.
- `ci/SmokeTest.java`: launch the real JAR seven times against temporary data.
- `ci/BuildEvidence.java`: records pseudonymous submission evidence for both outcomes.
- `Jenkinsfile`: Checkout -> Submission setup -> Test -> Build JAR -> Smoke test JAR -> Save artifact; always archive evidence afterward.

## Prerequisites

- Full JDK 21 (64-bit); not just a JRE.
- Command-line Git, including `git daemon`.
- No Maven, Gradle, Docker, database, web framework, or cloud account is needed.

## Setup instructions

```text
cd festpass-java
git init -b main
git add .
git commit -m "Add diagnostic FestPass starter"
```

## How to run the tests

```text
java ci/Build.java verify
```

This compiles main and test sources with `-Xlint:all -Werror`, runs the 28 tests,
writes `build/test-results/TEST-FestPass.xml`, and packages `build/festpass.jar`.

**Expected result after the fix: 28 tests, 0 failures, BUILD SUCCESS.**

## How to run the application

```text
java -jar build/festpass.jar help
java -jar build/festpass.jar add-event java "Java Workshop" 2
java -jar build/festpass.jar add-event music "Campus Music" 1
java -jar build/festpass.jar list
java -jar build/festpass.jar book java S1
java -jar build/festpass.jar book java S2
java -jar build/festpass.jar book java S3
java -jar build/festpass.jar cancel java S1
java -jar build/festpass.jar book java S3
java -jar build/festpass.jar roster java
java -jar build/festpass.jar summary
```

The first `book java S3` deliberately reports SOLD OUT and exits with code 2; after
cancellation the retry succeeds. Final summary: 2 events, 2 booked seats, 1 available seat.
Running `list` in a new terminal shows the same saved data.

## Jenkins pipeline configuration

The `Jenkinsfile` is a standard Pipeline script from SCM:

- Agent: `student-laptop` (a separate local build agent).
- SCM: Git, repository `git://127.0.0.1:9418/festpass-java`, branch `*/main`, script path `Jenkinsfile`.
- `triggers { pollSCM('* * * * *') }` checks Git once per minute and builds on detected
  changes, not on every timer tick. This is the only required pipeline change.
- Stages: Checkout -> Submission setup -> Test -> Build JAR -> Smoke test JAR -> Save artifact.
- A failed Test stage blocks Build JAR, Smoke test JAR, and Save artifact. The always-run
  `post` block still archives `build/submission-evidence.csv`.

## Original defect and fix

**Defect:** `BookingService.cancel()` validated that the student had an existing booking
but never removed it. The student stayed on the roster and the seat was never freed, so a
subsequent booking still saw the event as full.

```java
// Before (broken)
if (!event.students.contains(studentId)) {
    throw new IllegalArgumentException("No booking for student: " + studentId);
}
```

```java
// After (fixed)
if (!event.students.remove(studentId)) {
    throw new IllegalArgumentException("No booking for student: " + studentId);
}
```

Because `Registration.students` is a `LinkedHashSet`, `remove()` both deletes the student
and frees the seat, and the change is persisted by `BookingStore.save()`. The fix is the
smallest application-level change; no tests were modified, weakened, or removed.

## Test results (verified locally)

- `java ci/Build.java verify`: **28 tests, 0 failures**, `build/festpass.jar` generated.
- `java ci/SmokeTest.java`: seven real-JAR commands pass (add-event, book, sold-out,
  roster, cancel, book, summary).
- CLI walkthrough verified: booking, cancellation, seat reuse, roster, summary, and
  persistence across separate command invocations.

## Known limitations and steps that still require manual execution

- **Jenkins runtime:** This repository contains the configured `Jenkinsfile` and setup
  scripts, but the Jenkins controller, `student-laptop` agent, and local Git server were
  not run end to end in this environment. The pipeline is syntactically valid and follows
  the supplied specification; it has not been exercised to produce live build logs.
- **Automatic SCM-triggered builds:** Not verified without a running Jenkins. The
  `pollSCM('* * * * *')` trigger is committed and correct; a manual bootstrap build plus
  subsequent commits should produce automatic red/green builds in the lab environment.
- **Evidence CSVs:** `BuildEvidence.java` requires the Jenkins `JOB_NAME` and
  `BUILD_NUMBER` environment variables, so `submission-evidence.csv` can only be produced
  inside a running Jenkins build.