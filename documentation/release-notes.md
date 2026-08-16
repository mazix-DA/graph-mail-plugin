# Release notes

Overzicht van wijzigingen per versie van de Graph Mail-plugin.

## 1.0.2
- Publish-pipeline naar Maven Central gefixt: `cn.lalaki.central` werd alleen op het rootproject
  geregistreerd en niet op het `plugin`-subproject, waardoor `publishToCentralPortal` niet bestond
  voor het daadwerkelijk te publiceren artefact.
- Verplichte `allowedSenders`-whitelist toegevoegd (deny-by-default): elke verzending — ook via
  proces­data (`pv:`) — wordt geweigerd tenzij `senderMailbox` voorkomt op een kommagescheiden lijst
  van toegestane volledige adressen en/of `@domain`-entries in de pluginconfiguratie. Geldt ook voor
  het test-send endpoint. Bestaande pluginconfiguraties weigeren na de upgrade elke verzending totdat
  de whitelist eenmalig is ingevuld en opgeslagen.
- Token-cache verplaatst naar een gedeelde `GraphTokenCache`-bean, zodat caching daadwerkelijk werkt
  over plugin-instanties heen. Valtimo hydrateert per plugin-actie een nieuwe `GraphMailPlugin` (en
  dus voorheen een nieuwe `GraphMailClientImpl` met een eigen cache), waardoor een instance-eigen
  cache nooit hits opbouwde.
- `GraphMailClient.sendMail`-interface vereenvoudigd van 12 losse parameters naar de
  `GraphCredentials`/`OutboundMail`-parameterobjecten. Geen gedragswijziging in retry/backoff,
  draft+upload of foutafhandeling.
- Bug gefixt in de test-mail footer: door een verkeerd gebruikte escape-sequence (`${'$'}escapedSender`
  in plaats van gewone interpolatie) toonde elke testmail letterlijk de tekst `$escapedSender` in
  plaats van het daadwerkelijk gebruikte afzenderadres.

## 1.0.1
Correcties in de documentatie en kleine verbeteringen in de plugin.

## 1.0.0
Eerste publieke release: e-mail versturen via de Microsoft Graph API met OAuth2 (Client Credentials flow).
