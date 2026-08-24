# ChatGPT Local API for Android

A small Android app that exposes the official OpenAI API through a loopback-only,
OpenAI-compatible endpoint on the phone.

## Local endpoint

- Base URL: `http://127.0.0.1:8787/v1`
- Health: `http://127.0.0.1:8787/health`
- Chat Completions: `POST /v1/chat/completions`
- Responses: `POST /v1/responses`
- Models: `GET /v1/models`

The server binds to `127.0.0.1`, not `0.0.0.0`, so devices on the LAN cannot
connect to it.

## Setup

1. Install the APK.
2. Enter an OpenAI API key.
3. Keep upstream base URL as `https://api.openai.com/v1`.
4. Set default model, e.g. `chat-latest`.
5. Tap **Save & Start API**.
6. Point an OpenAI-compatible client on the same phone to
   `http://127.0.0.1:8787/v1`.

This app does not bypass ChatGPT website authentication, CAPTCHA, rate limits,
or anti-abuse controls. It uses the official API.
