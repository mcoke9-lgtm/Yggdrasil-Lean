# Independent Runtime Component Research

**Status:** Initial source review; candidates only, not adopted or device-tested.
**Branch:** `independent-builder-runtime-foundation-001`

## 1. Independence has more than one meaning

For this project, evaluate each component against four separate tests:

1. **Service independence:** Does it run without calling OpenAI, Google, or another hosted inference/voice API?
2. **Model-origin independence:** Are the model weights and training lineage independent of those providers? A locally executed model can still originate from a provider we intend to leave.
3. **Infrastructure control:** Can the user host, operate, back up, and replace it?
4. **Operational independence:** Does it continue when the internet, a vendor account, or one node is unavailable?

Do not call a component fully independent based on local execution alone.

## 2. Local language-model runtime candidates

### Ollama

Ollama documents a local API at `http://localhost:11434/api` and a local OpenAI-compatible endpoint at `http://localhost:11434/v1`. Its documentation also describes hosted cloud access, so deployment must explicitly use local models and must not silently route work to its cloud service.

- Strength: approachable model management and a stable local HTTP interface.
- Risk/qualification: local operation and cloud operation coexist in the product; configuration and tests must prove that the selected runtime never falls back to hosted models.
- Candidate use: first local model adapter for a user-controlled host, if the no-cloud condition is enforceable.

Sources:
- https://docs.ollama.com/api
- https://docs.ollama.com/api/openai-compatibility

### llama.cpp server

The llama.cpp project provides a lightweight C/C++ inference server, CPU and GPU execution, quantized model support, parallel decoding, and API-compatible endpoints.

- Strength: direct local inference, broad hardware flexibility, and less dependence on a model-management cloud service.
- Trade-off: more operational configuration may be needed than with Ollama.
- Candidate use: alternative backend and an important portability test for the model adapter.

Source:
- https://github.com/ggml-org/llama.cpp/tree/master/tools/server

**Protocol note:** An OpenAI-compatible HTTP shape is only a wire-protocol convention. It does not mean requests go to OpenAI. The client must use a local address and must not contain a hidden hosted fallback.

## 3. Offline speech recognition candidates

### sherpa-onnx — strongest initial candidate for an independent voice path

Its official documentation says Android speech recognition can run locally without internet. The project supports streaming and non-streaming speech recognition, text-to-speech, and multiple mobile and desktop platforms. Its repository identifies the project license as Apache-2.0.

- Strength: one toolkit can cover recognition and synthesis on Android and on other nodes; it avoids requiring Android's default speech-recognition provider.
- Trade-off: the chosen ONNX model still needs separate evaluation for accuracy, memory, latency, language support, and its own license.
- Candidate use: prototype voice input on the Galaxy S21 and a host-side speech service using the same message protocol.

Sources:
- https://k2-fsa.github.io/sherpa/onnx/android/index.html
- https://k2-fsa.github.io/sherpa/onnx/index.html
- https://github.com/k2-fsa/sherpa-onnx

### Vosk — lightweight alternative

Vosk documents offline recognition on Android and other small devices, streaming APIs, and compact language models.

- Strength: useful comparison point for low-resource operation and command vocabulary.
- Trade-off: accuracy must be tested against real conversational speech and the intended vocabulary.
- Candidate use: fallback or command-mode recognizer if tests show it meets requirements.

Sources:
- https://alphacephei.com/vosk/
- https://github.com/alphacep/vosk-api

### Important exclusion: whisper.cpp is not automatically OpenAI-independent

whisper.cpp is a local C/C++ implementation of the Whisper model and supports Android. It can avoid OpenAI-hosted inference, but Whisper itself is an OpenAI model family. Therefore it may satisfy **service independence** while failing the stronger **model-origin independence** test. Keep it as a technical benchmark only if the user explicitly accepts that distinction.

Source:
- https://github.com/ggml-org/whisper.cpp

## 4. Speech synthesis candidates

### sherpa-onnx TTS

The same toolkit supports offline text-to-speech on Android and other platforms. This is attractive for a shared speech interface, subject to testing the chosen voice model and its license.

Source:
- https://k2-fsa.github.io/sherpa/onnx/index.html

### Piper

Piper is a local neural text-to-speech engine. The current upstream repository is under the Open Home Foundation and is GPL-3.0. Voice models are distributed separately, and each voice's model card/license must be checked independently.

- Strength: local synthesis and a mature ecosystem of voices.
- Trade-off: GPL obligations and per-voice licensing need review before embedding or distributing it in the app.
- Candidate use: host-side voice output or a separately packaged component after license review.

Sources:
- https://github.com/OHF-Voice/piper1-gpl
- https://github.com/OHF-Voice/piper1-gpl/blob/main/COPYING

## 5. Android platform speech API caution

Android exposes `createOnDeviceSpeechRecognizer` and `isOnDeviceRecognitionAvailable` (API level 31+). The API explicitly says on-device creation fails when that capability is unavailable. The ordinary system recognizer is a separate path and uses the system's recognition service.

Therefore:
- Do not assume that enabling Voice Access means an independent speech engine is installed.
- Do not use the ordinary default recognizer as proof of offline operation.
- Probe on-device availability, then test in airplane mode.
- Prefer an app-bundled or explicitly managed offline engine when the Android service is unavailable or its provider is unacceptable.

Source:
- https://developer.android.com/reference/android/speech/SpeechRecognizer

## 6. Initial engineering conclusion

**Proposed, not adopted:** evaluate sherpa-onnx first for Android/offline speech; evaluate Ollama and llama.cpp as interchangeable local language-model backends; keep Vosk as a lightweight ASR comparator; assess Piper only after licensing review.

No specific model or voice is selected. Model-origin independence, hardware capacity, model/voice licensing, accuracy, and latency remain open evidence questions.

The Galaxy S21 is a client and test device, not assumed to be the main inference host. A host machine's CPU, RAM, GPU, storage, operating system, and power/network conditions must be measured before selecting model sizes.

## 7. Verification plan

1. Run local language inference with no OpenAI or Google credentials configured and with external network access blocked.
2. Verify logs and network traces show no hidden hosted fallback.
3. Run speech recognition and synthesis in airplane mode on the Android device.
4. Record model name, source, license, size, memory use, latency, and accuracy test results.
5. Repeat the same protocol tests with a second model backend.
6. Remove or disable one node and verify that identity, memory, and local communication remain usable.

**Evidence status:** source documentation reviewed; no candidate has been installed, integrated, benchmarked, or verified on the user's devices.
