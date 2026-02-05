# Plan : Automatisation du déploiement Keycloak

Le problème actuel est que les `ConfigMaps` ont été créés depuis tes fichiers locaux. Sur une machine distante (ton serveur de prod), ces fichiers n'existent pas, donc le `apply` ne suffira pas.

## Solutions possibles

### Option 1 : Image Keycloak Personnalisée (Recommandée) ✅
On crée un `Dockerfile` qui hérite de Keycloak et qui contient déjà tes thèmes et tes plugins.
- **Le workflow** : Tu buildes l'image -> Tu la pushes sur ton registry -> Sur le serveur, tu fais `kubectl apply` et il pull l'image complète.
- **Avantage** : C'est ce qu'il y a de plus standard et de plus robuste. Aucune gestion de fichiers externes.

### Option 2 : Init Container
On garde l'image officielle Keycloak, mais on ajoute un second petit container ("init container") qui porte tes fichiers et les copie au démarrage dans un volume partagé.
- **Avantage** : Permet de changer de version de Keycloak sans re-build l'image de config.
- **Inconvénient** : Le YAML devient un peu plus complexe.

### Option 3 : CI/CD (Pipeline)
Ton outil de CI/CD (ex: GitHub Actions) génère le YAML des ConfigMaps à la volée avant de faire le `kubectl apply`.
- **Inconvénient** : Le JAR du plugin pollue un peu le YAML (base64).

---

## Ma recommandation : L'image personnalisée

Je vais créer un `Dockerfile` dans `calendar-app-identity-service-config` qui fait tout ça. Une fois buildé, ton déploiement sera aussi simple que :
```yaml
image: wely-keycloak:latest
# Plus besoin de volumeMounts compliqués pour les thèmes !
```

Est-ce que ça te convient ? Si oui, je m'occupe de créer le `Dockerfile` et de nettoyer le `wely-stack.yaml`.
