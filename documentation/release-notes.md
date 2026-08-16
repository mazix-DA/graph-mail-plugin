# Release notes

Overzicht van wijzigingen per versie van de Graph Mail-plugin.

## 1.0.2
Beveiligingshardening van de CI/CD-pipeline.
- De GitHub Actions workflows `publish-backend.yaml` en `publish-frontend.yaml` interpoleerden
  `${{ matrix.value }}` en step-outputs rechtstreeks in `run:`-scripts. Omdat `${{ }}` als platte
  tekst wordt gesubstitueerd vóórdat de shell (of, in het frontend-script, Node.js) het script
  parseert, was dit een script-injectiepad (shell- en JS-string-injectie) via directorynamen uit de
  changed-files diff. Alle interpolaties gaan nu via `env:` en worden als quoted shell-variabele
  gelezen; het `node -e` script in de frontend-publicatie leest `process.env.CHANGED_DIR` in plaats
  van de waarde in de JS-broncode te splitsen.
- `tj-actions/changed-files` was gepind op een mutable tag (`v45`). Deze action's tags zijn in maart
  2025 gecompromitteerd (CVE-2025-30066) om CI-secrets in workflow-logs te dumpen. Gepind op de
  actuele, geverifieerde commit-SHA zodat een toekomstige tag-herpointing niet stilzwijgend andere
  code kan uitvoeren in een pipeline die de Sonatype- en npm-publicatiesecrets gebruikt.

## 1.0.1
Correcties in de documentatie en kleine verbeteringen in de plugin.

## 1.0.0
Eerste publieke release: e-mail versturen via de Microsoft Graph API met OAuth2 (Client Credentials flow).
