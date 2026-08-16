# Release notes

Overzicht van wijzigingen per versie van de Graph Mail-plugin.

## 1.0.2
- Token-cache verplaatst naar een gedeelde `GraphTokenCache`-bean, zodat caching daadwerkelijk werkt
  over plugin-instanties heen. Valtimo hydrateert per plugin-actie een nieuwe `GraphMailPlugin` (en
  dus voorheen een nieuwe `GraphMailClientImpl` met een eigen cache), waardoor een instance-eigen
  cache nooit hits opbouwde.
- `GraphMailClient.sendMail`-interface vereenvoudigd van 12 losse parameters naar de
  `GraphCredentials`/`OutboundMail`-parameterobjecten. Geen gedragswijziging in retry/backoff,
  draft+upload of foutafhandeling.

## 1.0.1
Correcties in de documentatie en kleine verbeteringen in de plugin.

## 1.0.0
Eerste publieke release: e-mail versturen via de Microsoft Graph API met OAuth2 (Client Credentials flow).
