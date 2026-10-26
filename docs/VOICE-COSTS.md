# Walk mode: what it costs and how it could cost less

Status 2026-10-26. Done: model picker, fewer false turns, usage tracking (see [PROGRESS.md](PROGRESS.md),
"Cost work"). Everything under **Deferred** is written down only: **not started, on purpose.** Decide with
real numbers from the usage readout (Settings > Dictation and walk mode > Usage) and the OpenAI billing
page first.

## How the Realtime API bills

- Per token, not per connected minute. Server VAD drops silence, so an idle open session costs
  nothing (or close to it). Background noise that the VAD takes for speech is billed, and each such
  turn also triggers a full reply.
- User audio is about 10 tokens a second (600 a minute), the model's audio about 20 (1,200 a minute).
- **Every reply re-reads the whole conversation as input.** The repeated part is usually cached and
  billed at a fraction of the rate; a long conversation still grows the cost of every new reply.
- The transcript of what the user said (`gpt-4o-mini-transcribe`) is billed separately, about
  $0.003 per minute of speech.

List prices per 1M tokens (OpenAI pricing page, 2026-10-26; also in `RealtimeProtocol.MODELS`):

| Model | Audio in | Audio out | Cached in (audio / text) | Text in / out |
|---|---|---|---|---|
| gpt-realtime-2, gpt-realtime-2.1 | $32 | $64 | $0.40 / $0.40 | $4 / $24 |
| gpt-realtime-2.1-mini | $10 | $20 | $0.30 / $0.06 | $0.60 / $2.40 |

The model speaking (about $0.08 a minute on the full models) is the largest share of a typical day.

## Estimate: 3 hours a day for 300 days

Walk mode on for 3 h a day; most of it silence. Assumes most of the re-read context is billed at the
cached rate (if it is not, long conversations cost several times more).

| Talking within the 3 h | gpt-realtime-2 / 2.1 | 2.1 mini |
|---|---|---|
| Light: user ~10 min, model ~15 min | ~$1.80/day, ~$540 | ~$0.70/day, ~$210 |
| Typical: ~27 min / ~36 min | ~$4.50/day, ~$1,350 | ~$1.70/day, ~$510 |
| Heavy: ~45 min / ~60 min | ~$7.50/day, ~$2,250 | ~$2.80/day, ~$840 |

These are estimates made before the usage readout existed; replace them with measured numbers.

## Deferred optimizations (not started)

Roughly in order of expected saving per effort.

1. **Shorter context.** Trim what the workspace functions return (screen dumps are re-read on every
   reply) and set `truncation` with a `retention_ratio` (or `token_limits.post_instructions`) so long
   walks do not keep growing the input. Trade-off: truncating breaks the cache near the start of the
   conversation and the model forgets older turns.
2. **Idle auto-stop.** End walk mode after N minutes without `speech_started`, so a session left on in a
   pocket cannot pile up false turns.
3. **Shorter answers.** Tighter instructions and `max_output_tokens` as a hard cap: the model's audio is
   the most expensive part.
4. **Push-to-talk** (a button, a Bluetooth clicker or the headset button through a media session): VAD
   off, audio sent only while pressed, `input_audio_buffer.commit` + `response.create` on release.
   Saves only what false turns cost (silence is already free): little in quiet places, maybe 10-20 %
   on a noisy street. Mostly a reliability gain.
5. **Economy engine: text in, text out.** Push-to-talk, the phone's own `SpeechRecognizer` (free, already
   used by Dictate), a text model with the same functions, then speech from platform TTS (free) or
   OpenAI TTS (about $0.015 a minute). Audio tokens are what is expensive, so this is the big lever:
   roughly $0.20-0.60 a typical day instead of ~$4.50 (~$60-180 for the 300 days). Trade-offs: slower
   first audio, weaker recognition of technical terms, clumsier interrupting, a flatter voice with
   platform TTS. Would sit next to Realtime as a choice, sharing the tools and the walk screen.

## Known gap in the usage readout

A reply still running when walk mode is stopped is billed but not counted: its `response.done` arrives
after the socket is closed. Small; noted so the estimate is read as a slight undercount.
