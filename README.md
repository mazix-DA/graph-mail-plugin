# Graph Mail Plugin

Valtimo plugin voor het versturen van e-mail via de Microsoft Graph API met OAuth2 (Client Credentials flow).

## Documentatie

- [Getting Started](documentation/getting-started.md) — installatie en buildinstructies
- [Plugin Documentatie](documentation/plugin.md) — pluginconfiguratie, acties en aandachtspunten

## Vereisten

- Valtimo 13.x
- Azure App Registration met de applicatiemachtigingen:
  - `Mail.Send` — vereist voor alle e-mailverzendingen
  - `Mail.ReadWrite` — vereist voor bijlagen groter dan 3 MB
- Client secret aangemaakt onder *Certificates & secrets*

## Installatie

### Backend

Voeg toe aan `build.gradle.kts`:

```kotlin
implementation("com.ritense.valtimoplugins:graph-mail:1.0.0")
```

Voeg toe aan `application.yml`:

```yaml
operaton:
  bpm:
    job-executor:
      core-pool-size: 20
      max-pool-size: 50
```

### Frontend

```shell
npm install @valtimo-plugins/graph-mail
```

Voeg de module en specificatie toe aan je `AppModule`:

```typescript
import { NgModule } from '@angular/core';
import { PLUGINS_TOKEN } from '@valtimo/plugin';
import { GraphMailPluginModule, graphMailPluginSpecification } from '@valtimo-plugins/graph-mail';

@NgModule({
  imports: [
    // ... andere imports
    GraphMailPluginModule,
  ],
  providers: [
    { provide: PLUGINS_TOKEN, useValue: [graphMailPluginSpecification] },
  ],
})
export class AppModule {}
```

## Snel overzicht

| | |
|---|---|
| **Protocol** | OAuth2 Client Credentials → Microsoft Graph API |
| **Verzendpaden** | Inline (≤ 3 MB bijlagen) en upload-sessie (> 3 MB, tot 25 MB) |
| **Authenticatie** | Per tenant/client gecachte access tokens |
| **Logging** | PII-gemaskeerd, apart auditlogger (`entra.plugin.audit`) |
