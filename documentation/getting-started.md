# Getting Started

## Prerequisites

- Java 21
- Node.js >= 20, < 23
- Valtimo 13.x
- Azure App Registration met de applicatiemachtigingen:
  - `Mail.Send` — vereist voor alle e-mailverzendingen
  - `Mail.ReadWrite` — **alleen** nodig voor bijlagen groter dan 2 MiB; laat deze machtiging weg als je geen grote bijlagen verstuurt (least privilege)

> **Aanbevolen:** beperk de app registration aan de Exchange Online-kant tot de functionele
> mailboxen die de plugin gebruikt via een Application Access Policy, en vul in de
> pluginconfiguratie de verplichte `allowedSenders`-whitelist in.
> Zie [Plugin Documentatie](plugin.md) voor details.

## Plugin development

The plugin source code is located in:

- Backend: `backend/plugin/src/`
- Frontend: `frontend/projects/plugin/src/`

For more information on how to build a plugin, see
the [Custom Plugin Definition](https://docs.valtimo.nl/features/plugins/plugins/custom-plugin-definition) documentation.

## Build

### Backend

All commands below should be run from the repository root — the Gradle wrapper lives there,
not in `backend/`.

```shell
./gradlew build
```

Tests uitvoeren (vereist Docker voor de test-database):

```shell
./gradlew :backend:plugin:test
```

### Frontend

```shell
cd frontend
npm install
npm run build
```

De pluginlibrary specifiek bouwen of testen:

```shell
npx ng build @valtimo-plugins/graph-mail
npx ng test @valtimo-plugins/graph-mail --watch=false
```

## Installatie in je Valtimo-project

### Backend

Voeg de volgende dependency toe aan je `build.gradle.kts`:

```kotlin
implementation("com.ritense.valtimoplugins:graph-mail:1.0.4")
```

Vul in je `application.yml` het `operaton`-blok aan:

```yaml
operaton:
  bpm:
    job-execution:
      core-pool-size: 20
      max-pool-size: 50
      queue-capacity: 10
```

> **Samenvoegen, niet toevoegen.** Heeft je `application.yml` al een `operaton:`-sleutel — en dat is bij een GZAC-project vrijwel altijd zo — voeg deze instellingen dan tóe aan dat bestaande blok. Een tweede `operaton:` op het hoogste niveau is ongeldige YAML en laat de applicatie bij opstarten crashen met `found duplicate key operaton`.

> **Let op de sleutelnaam:** `job-execution`, niet `job-executor`. Spring negeert een onbekende sleutel stilzwijgend, dus een typefout hier levert geen foutmelding op — de engine blijft dan gewoon op zijn standaarden van 3 en 10 draaien. Controleer bij het opstarten of er `Setting up jobExecutor with corePoolSize=20, maxPoolSize:50` in de log staat.

> **Waarom:** een verzending bezet een job-executor thread zolang de Graph-aanroep loopt, en bij bijlagen boven 2 MiB zolang de upload duurt. Zie [Plugin Documentatie](plugin.md) voor details.

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

> Als je meerdere plugins registreert, combineer je de `useValue`-arrays of gebruik je `multi: true`:
> ```typescript
> { provide: PLUGINS_TOKEN, useValue: [graphMailPluginSpecification, anderePluginSpecification] }
> ```
