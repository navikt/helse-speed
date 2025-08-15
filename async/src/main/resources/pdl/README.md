# Oppdatere skjema

Få tak i en aiven-secret som har registry brukernavn og passord, og bruk f.eks. curl:

```
curl -v -u "$REGISTRY_USER:$REGISTRY_PASSWORD" "$URL/subjects/pdl.leesah-v1-value/versions/$VERSION"
```

`$VERSION` vil da være `1` for første versjon, `2` for andre osv.