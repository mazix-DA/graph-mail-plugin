# Release notes

Overzicht van wijzigingen per versie van de Graph Mail-plugin.

## 1.0.2
- Publish-pipeline naar Maven Central gefixt: `cn.lalaki.central` werd alleen op het rootproject
  geregistreerd en niet op het `plugin`-subproject, waardoor `publishToCentralPortal` niet bestond
  voor het daadwerkelijk te publiceren artefact.

## 1.0.1
Correcties in de documentatie en kleine verbeteringen in de plugin.

## 1.0.0
Eerste publieke release: e-mail versturen via de Microsoft Graph API met OAuth2 (Client Credentials flow).
