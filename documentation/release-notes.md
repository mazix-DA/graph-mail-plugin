# Release notes

Overzicht van wijzigingen per versie van de Graph Mail-plugin.

## 1.0.2
- De rate-limiter van `/api/v1/plugin/entra/test-send` hield voor elke gebruiker die ooit een
  testmail heeft verstuurd blijvend een entry aan in een in-memory store die nooit kromp — op een
  langlopende instantie met veel verschillende admins groeide deze onbegrensd. Verouderde entries
  (buiten het rate-limit-venster) worden nu periodiek opgeruimd zodra de store een omvangsdrempel
  overschrijdt.

## 1.0.1
Correcties in de documentatie en kleine verbeteringen in de plugin.

## 1.0.0
Eerste publieke release: e-mail versturen via de Microsoft Graph API met OAuth2 (Client Credentials flow).
