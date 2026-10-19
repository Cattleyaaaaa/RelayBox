# RelayBox Web Console

React + TypeScript dashboard for the RelayBox webhook delivery API.

## Local development

```powershell
npm install
npm run dev
```

Vite serves the UI at `http://localhost:5173` and proxies `/api` and `/actuator` to the backend at `http://localhost:8080`.

## Production build

```powershell
npm run build
```

The Docker image builds the Vite app and serves it through Nginx. Nginx proxies API and Actuator requests to the `app` Compose service.

The UI uses the React Bits `BlurText` and `AnimatedContent` components for the animated title and staggered content reveals.
