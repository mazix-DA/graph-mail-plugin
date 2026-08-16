# Release notes

Overzicht van wijzigingen per versie van de Graph Mail-plugin.

## 1.0.2
- Afzenderadres gemaskeerd in het `GraphMailEmailSentEvent` dat het test-send endpoint publiceert.
  De reguliere verzendactie maskeerde dit al consequent in zowel het succes- als het faal-event;
  het test-send endpoint publiceerde per ongeluk het volledige, ongemaskeerde adres, zichtbaar voor
  elke listener die naar dit event luistert.

## 1.0.1
Correcties in de documentatie en kleine verbeteringen in de plugin.

## 1.0.0
Eerste publieke release: e-mail versturen via de Microsoft Graph API met OAuth2 (Client Credentials flow).
