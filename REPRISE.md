# 🚀 REPRISE — Il ne reste que 2 choses à faire

> Ce guide reprend **exactement là où vous en êtes**. Le plus long est déjà fait ✅
> Temps prévu : **10 minutes**. Copiez-collez, aucune connaissance de Git requise.

---

## 📍 Où vous en êtes (vérifié)

J'ai inspecté votre machine et GitHub, voici l'état réel :

| Point | État |
|---|---|
| La nouvelle clé `nouveau-release.jks` existe | ✅ **fait** |
| Elle est bien ignorée par Git (jamais envoyée sur GitHub) | ✅ **sûr** |
| Le code est corrigé et poussé | ✅ **fait** |
| La CI (« Android CI ») est **verte** | ✅ **fait** |
| Les 4 secrets GitHub `KEYSTORE_*` sont configurés | ❓ **à vérifier** (étape 1) |
| L'ancienne clé est retirée de l'historique | ❌ **à faire** (étape 2) |

**Bonne nouvelle :** votre nouvelle clé est en sécurité. Elle est dans le dossier du projet
`nouveau-release.jks`, et Git refuse de l'envoyer (la règle `*.jks` du `.gitignore` fonctionne).

**Il reste donc 2 choses :** vérifier que les secrets marchent, puis nettoyer l'historique.

---

## 1️⃣ Vérifier que les 4 secrets fonctionnent

> **Pourquoi ?** C'est ce qui permet à GitHub de signer les APK avec votre **nouvelle** clé.
> Si un secret est mal recopié, la publication échouera et l'app ne pourra plus se mettre à jour.

### 1.1 Lancer le test
> 🚫 **Ces adresses ne marchent PAS** — c'est normal, elles n'existent pas :
> `.../actions/manual` • `.../actions/workflows/release.yml` • `.../actions/workflows/release.yml/dispatches`
>
> ✅ **La seule adresse qui marche :**
> **[github.com/Souxch06/Watermark_remover/actions](https://github.com/Souxch06/Watermark_remover/actions)**
> ⬅️ **le `/actions` à la fin est obligatoire**

**5 clics, aucune autre adresse à taper :**

1. Ouvrez **[github.com/Souxch06/Watermark_remover/actions](https://github.com/Souxch06/Watermark_remover/actions)**
2. Si GitHub propose **« Sign in »** (en haut à droite), **connectez-vous d'abord** :
   sans connexion, cette page renvoie « 404 » même si tout va bien.
3. **Colonne de gauche**, sous le mot « Workflows » : cliquez sur **« Release »**.
4. Sur la page « Release », à droite du titre : cliquez sur le bouton gris **« Run workflow » ▾**.
5. Un panneau se déplie : tapez ``1.4.0`` dans la case, puis cliquez sur le bouton **vert
   « Run workflow »** du panneau.
6. **Attendez 5 à 10 minutes**, puis appuyez sur **F5** pour rafraîchir la page.

**Ce que vous devez voir, clic par clic :**

| Étape | Ce qui doit s'afficher |
|---|---|
| 1 | Une liste de runs, et à gauche la liste : **Android CI**, **Release** |
| 3 | Le grand titre **« Release »** en haut de la page |
| 4 | Un petit panneau avec une case de texte + un bouton vert |

**Si quelque chose ne s'affiche pas :**

- ❌ **Page « 404 » / « Page not found »** → l'adresse ne finit pas par `/actions`, ou vous n'êtes pas
  connecté. Retapez **à la main** dans la barre du navigateur :
  `github.com/Souxch06/Watermark_remover/actions`
- ❌ **Colonne de gauche vide (pas de « Release »)** → cliquez sur **« All workflows »** en haut de
  cette colonne.
- ❌ **Pas de bouton « Run workflow »** → vous êtes sur le mauvais workflow. Le titre affiché doit
  être **« Release »** (⚠️ pas « Android CI », qui en a un aussi).
- ❌ **Pas d'onglet « Actions » du tout** → **Settings → Actions → General** → cochez
  **« Allow all actions and reusable workflows »** → **Save**.

> ⏭️ **Vous pouvez aussi sauter ce test.** Il sert *uniquement* à vérifier vos 4 secrets. L'**étape 2**
> (nettoyage de l'historique) ne dépend **pas** de l'onglet Actions : faites-la d'abord si vous
> préférez, et revenez ici plus tard.

### 1.2 Lire le résultat

| Ce que vous voyez | Signification | Quoi faire |
|---|---|---|
| ✅ **Coche verte** | Vos 4 secrets sont bons | Passez à l'**étape 2** |
| ❌ Rouge : `KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD secrets are not configured` | Il manque des secrets | Faites **1.3** ci-dessous |
| ❌ Rouge : `refusing to publish an APK that would be signed with the debug key` | Même chose : la clé n'est pas trouvée | Faites **1.3** |
| ❌ Rouge : `keytool error` ou `Keystore was tampered with` | Mot de passe erroné | Faites **1.3**, vérifiez `KEYSTORE_PASSWORD` |
| ❌ Rouge : `Invalid version` | La version est mal écrite | Recommencez avec exactement `1.4.0` |
| ❌ Libre : `Unit tests → Unresolved reference` ou autre erreur de code | Ce n'est pas vos secrets, c'est le code | Montrez-moi le message : c'est moi qui corrige |

Pour voir **laquelle** des étapes a échoué : cliquez sur le run rouge, puis sur la ligne rouge
(« ✗ ») dans la liste de gauche. Elle se déplie et montre l'erreur en clair.

### 1.2 bis 🆘 « La page Run workflow renvoie une erreur / 404 »

Trois causes possibles, dans l'ordre de probabilité :

**a) Vous n'êtes pas connecté à GitHub.** → Connectez-vous, puis rouvrez la page.
Les pages Actions d'un dépôt public sont invisibles pour les visiteurs non connectés.

**b) Vous avez ouvert un lien avec `.yml` dans l'adresse.** → Ces liens
(`.../actions/workflows/release.yml`) ne s'ouvrent **pas** dans un navigateur. Utilisez la
navigation par clics de 1.1 : onglet **Actions** → **Release** dans la colonne de gauche.

**c) Le dépôt n'est pas le vôtre / l'onglet Actions est masqué.** → Vérifiez que l'onglet
**Actions** est bien présent sur [la page du dépôt](https://github.com/Souxch06/Watermark_remover).
S'il manque, allez dans **Settings → Actions → General** et choisissez
« Allow all actions and reusable workflows », puis **Save**.

> ⚠️ **Ce qu'il ne faut PAS conclure :** cette erreur de page **n'a rien à voir** avec vos secrets
> GitHub, ni avec la clé, ni avec le nettoyage de l'historique. C'est uniquement un problème
d'affichage de page. Vous pouvez passer directement à l'**étape 2** si vous préférez : elle ne
dépend pas du tout de l'onglet Actions.

### 1.3 (Seulement si c'est rouge) Recréer les 4 secrets
1. Allez sur **[la page du dépôt](https://github.com/Souxch06/Watermark_remover)** → onglet
   **« Settings »** (en haut, à droite) → dans la colonne de gauche : **« Secrets and variables »**
   → **« Actions »**.
2. Supprimez les secrets existants s'il y en a (icône corbeille à droite de chacun).
3. Créez les 4 à nouveau avec **« New repository secret »** :

| `Name` (exactement, en majuscules) | `Secret` (le contenu) |
|---|---|
| `KEYSTORE_BASE64` | le texte ci-dessous ⬇️ |
| `KEYSTORE_PASSWORD` | **le mot de passe que vous avez choisi** quand vous avez créé la clé |
| `KEY_ALIAS` | `watermark` |
| `KEY_PASSWORD` | le **même** mot de passe |

**Pour obtenir le texte `KEYSTORE_BASE64`**, dans le terminal de VS Code :

```powershell
cd "$HOME\OneDrive\Documents\VSCODE\Watermark_remover"
$base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes("nouveau-release.jks"))
Set-Clipboard $base64
Write-Host "✅ Copié ! Collez-le dans le secret KEYSTORE_BASE64" -ForegroundColor Green
```

> 💡 Le texte est **très long** (une seule ligne interminable). C'est normal : collez-le en entier.
>
> ⚠️ **L'ALIAS doit être `watermark`.** Si vous aviez tapé autre chose au moment de la création,
> utilisez exactement ce que vous aviez tapé.
>
> ❓ **Vous ne vous souvenez plus du mot de passe ?** Pas de panique : recommencez l'étape de
> création de la clé (voir le guide complet [TUTO-SECURITE.md](TUTO-SECURITE.md), étape A.1) avec
> un mot de passe que vous noterez cette fois. **Mais sauvegardez celui-ci à 2 endroits !**

### 1.4 Relancer le test

Refaites **1.1**. Vous devez obtenir du vert. Ne passez pas à l'étape 2 avant.

---

## 2️⃣ Retirer l'ancienne clé de l'historique

> **Pourquoi ?** Même supprimée du code, l'ancienne clé reste **consultable** dans les anciennes
> versions des fichiers. N'importe qui peut la télécharger et fabriquer une fausse mise à jour.
> Il faut réécrire l'historique pour la faire disparaître complètement.

### 2.1 Installer l'outil

```powershell
python -m pip install git-filter-repo
```

> ❌ Si ça répond *« python n'est pas reconnu »*, installez Python depuis
> [python.org/downloads](https://www.python.org/downloads/) (cochez **« Add Python to PATH »**
> pendant l'installation), puis **rouvrez VS Code**.

Vérifiez que ça marche :

```powershell
git filter-repo --version
```

> ❌ Si *« git filter-repo n'est pas reconnu »*, ajoutez-le au chemin :
> ```powershell
> $env:PATH += ";$env:LOCALAPPDATA\Programs\Python\Python312\Scripts"
> git filter-repo --version
> ```

### 2.2 Faire une copie de secours (30 secondes, ne sautez pas)

```powershell
cd "$HOME\OneDrive\Documents\VSCODE"
git clone Watermark_remover Watermark_remover_SAUVEGARDE
cd Watermark_remover
```

### 2.3 ⚠️ Vérifier que le dossier est « propre »

L'outil refuse de travailler si des fichiers sont en cours de modification :

```powershell
git status
```

- Si vous voyez `nothing to commit, working tree clean` → **parfait**, continuez.
- Si vous voyez des fichiers en rouge → mettez-les de côté :
  ```powershell
  git stash
  ```
  puis relancez `git status`.

### 2.4 Nettoyer l'historique

```powershell
git filter-repo --sensitive-data-removal --path signing/release.jks --path signing/keystore.properties --path .vscode/ --invert-paths
```

> **Décryptage :** `--invert-paths` = « supprime ces fichiers ». `--sensitive-data-removal` =
> mode spécial données sensibles recommandé par l'outil.

Vous devez voir `Parsed NN commits` puis `New history written`. ✅

### 2.5 Vérifier que c'est bien parti

```powershell
git log --all --name-status -- signing/release.jks
```

Cette commande doit **n'afficher AUCUNE ligne**.
- ✅ Rien du tout → la clé n'est plus dans l'historique local.
- ❌ Des lignes → relancez 2.4.

### 2.6 Envoyer sur GitHub (⚠️ étape sensible)

```powershell
git remote add origin https://github.com/Souxch06/Watermark_remover.git
git push --force --mirror origin
```

> ⚠️ `--force` **écrase** l'historique GitHub. **C'est justement le but** : c'est la seule façon de
> supprimer définitivement la clé.
>
> Si GitHub demande un mot de passe : **ce n'est pas votre mot de passe GitHub**, c'est un
> **token**. Voir la section « Le push refuse mon mot de passe » plus bas.

✅ Vous devez voir `+ ... -> main (forced update)`.

### 2.7 Vérifier que GitHub n'a plus la clé ⭐

**Le test qui prouve que tout est fini.** Ouvrez ce lien :

**https://github.com/Souxch06/Watermark_remover/blob/ac1e648/signing/release.jks**

| Ce que vous voyez | Signification |
|---|---|
| **404 — This is not the file you are looking for** | 🎉 **TERMINÉ.** La clé a disparu |
| Le fichier s'affiche encore | Le nettoyage n'a pas marché → refaites 2.4 puis 2.6 |

### 2.8 Remettre votre dossier local d'aplomb

L'historique ayant été réécrit, resynchronisez :

```powershell
git fetch origin
git reset --hard origin/main
git status
```

Vous devez voir `Your branch is up to date with 'origin/main'` et **rien en rouge**.

---

## 🆘 Problèmes courants

### « Le push refuse mon mot de passe »

GitHub n'accepte plus les mots de passe. Il faut un **token** :

1. Allez sur **https://github.com/settings/tokens**
2. **« Generate new token (classic) »**
3. Cochez la case **`repo`** (la première en haut).
4. En bas, **« Generate token »**, puis **copiez** ce qui commence par `ghp_`.
5. Au moment du push : `Username` = votre nom GitHub, `Password` = **collez le token**.

Pour ne plus le retaper :

```powershell
git config --global credential.helper manager
```

### « filter-repo dit : not a fresh clone / stash not empty »

Il veut un dossier propre. Faites :

```powershell
git status
git stash
```

puis relancez 2.4.

### « Vous êtes sûr de ce `--force` ? »

Oui. Sans lui, GitHub garderait l'ancien historique donc la clé. Et votre sauvegarde (2.2) est là
en cas de pépin.

### « Mes utilisateurs déjà installés ? »

Ils devront **désinstaller une fois**, puis réinstaller. C'est inévitable : la nouvelle clé est
différente, Android refuse de mettre à jour vers une signature différente. Mettez ce texte dans
les notes de version :

```
⚠️ Nouvelle clé de sécurité : désinstallez l'ancienne version avant d'installer celle-ci
   (une seule fois). Vos vidéos déjà traitées restent dans la galerie.
```

---

## ✅ Récapitulatif

| # | Action | Fait |
|---|---|---|
| 1 | Lancer le workflow **Release** et vérifier que c'est **vert** | ☐ |
| 2 | (si rouge) Recréer les 4 secrets | ☐ |
| 3 | Installer `git-filter-repo` | ☐ |
| 4 | Faire la copie de secours (`Watermark_remover_SAUVEGARDE`) | ☐ |
| 5 | Lancer `git filter-repo` | ☐ |
| 6 | `git push --force --mirror origin` | ☐ |
| 7 | **Vérifier le lien 404** | ☐ |
| 8 | `git reset --hard origin/main` | ☐ |

**Quand l'étape 7 affiche 404 : le problème de sécurité est entièrement résolu.** 🎉

---

## 💬 Ce que je ne peux pas faire à votre place

- **Votre mot de passe de clé** : je ne le connais pas (et c'est très bien ainsi).
- **La page des secrets GitHub** : elle demande votre connexion.
- **Le `git push --force`** : il demande vos identifiants GitHub.

Si une étape bloque, notez **le message d'erreur exact** et revenez me le montrer : je vous dirai
quoi faire.

---

*Guide pour `Souxch06/Watermark_remover`. Pour repartir de zéro (recréer une clé), voir
[TUTO-SECURITE.md](TUTO-SECURITE.md).*
