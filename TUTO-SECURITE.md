# 🔧 TUTO — Régler le problème de la clé de signature

> **En une phrase :** la clé qui signe l'application a été publiée sur GitHub par erreur.
> Il faut la **remplacer** et **nettoyer l'historique**. Ce guide fait tout, étape par étape.

⏱️ Temps prévu : **20 à 30 minutes**. Aucune connaissance de Git n'est nécessaire, copiez-collez.

> 👉 **La clé est déjà créée ?** Allez directement à **[REPRISE.md](REPRISE.md)**, la version
> courte (10 min) qui ne traite que ce qui reste : vérifier les secrets puis nettoyer l'historique.

---

## 🤔 C'est quoi le problème ?

Quand vous installez une application Android, le téléphone vérifie qu'elle est **signée** par
une clé. C'est cette clé qui prouve que « c'est bien la même application » et qui permet
d'installer une mise à jour **par-dessus** l'ancienne sans tout perdre.

Le fichier de cette clé (`signing/release.jks`) était rangé **dans le dépôt GitHub public**, avec
son mot de passe. N'importe qui pouvait donc :

1. télécharger la clé,
2. fabriquer une fausse application malveillante,
3. la signer avec cette clé,
4. la publier comme « mise à jour ».

Le téléphone des utilisateurs aurait accepté cette fausse mise à jour, parce que la signature
aurait été valide. **C'est le problème.**

### Ce qui est déjà réglé ✅

J'ai déjà retiré ces fichiers **du code actuel**, et j'ai rendu impossible la publication d'un APK
signé avec la mauvaise clé. Le dépôt est propre *maintenant*.

### Ce qui reste à faire ⚠️

**La clé est toujours dans l'historique.** C'est le point important : même supprimés, les fichiers
restent visibles dans les anciennes versions des commits, accessibles par une simple URL. Tant
qu'on n'a pas nettoyé l'historique **et** changé la clé, le danger reste entier.

Il y a donc **2 étapes** :
- **Étape A** → créer une nouvelle clé (pour rendre l'ancienne inutile)
- **Étape B** → nettoyer l'historique (pour retirer l'ancienne du dépôt)

---

## 📋 Étape 0 — Se préparer (5 min)

### 0.1 Ouvrir un terminal

Dans VS Code : menu **Terminal** → **Nouveau terminal**.
Les commandes ci-dessous se tapent **une par une** (pas tout d'un coup).

### 0.2 Vérifier que Git fonctionne

```powershell
git --version
```

Vous devez voir quelque chose comme `git version 2.x`.
Si Windows répond *« git n'est pas reconnu »*, installez Git depuis
[git-scm.com/download/win](https://git-scm.com/download/win), puis **fermez et rouvrez** VS Code.

### 0.3 Se placer dans le bon dossier

```powershell
cd "$HOME\OneDrive\Documents\VSCODE\Watermark_remover"
```

Vérifiez que vous êtes au bon endroit (vous devez voir `app`, `README.md`, etc.) :

```powershell
ls
```

### 0.4 Vérifier que tout est à jour

```powershell
git pull
```

> 💡 Si Git vous ouvre un éditeur de texte bizarre (souvent **Vim**) : tapez `:wq` puis **Entrée**
> pour fermer.

---

## 🔑 Étape A — Créer une nouvelle clé (10 min)

> **Pourquoi ?** Une clé ne peut pas être « réparée ». Une fois publique, elle est perdue pour
> toujours. On en fabrique une neuve et on jette l'ancienne.

### A.1 Générer la nouvelle clé

```powershell
keytool -genkeypair -v -keystore nouveau-release.jks -alias watermark -keyalg RSA -keysize 2048 -validity 10000
```

> ❌ Si Windows dit *« keytool n'est pas reconnu »* : utilisez le chemin complet, en remplaçant
> `VOTRE_NOM` par votre nom d'utilisateur Windows :
> ```powershell
> & "C:\Program Files\Java\jdk-17\bin\keytool.exe" -genkeypair -v -keystore nouveau-release.jks -alias watermark -keyalg RSA -keysize 2048 -validity 10000
> ```

keytool va poser quelques questions :

| Question | Quoi répondre |
|---|---|
| `Enter keystore password:` | **choisissez un mot de passe** et **notez-le** |
| `Re-enter new password:` | le même |
| `What is your first and last name?` | votre nom (ou ce que vous voulez) |
| `What is the name of your organizational unit?` | peut rester vide → **Entrée** |
| `What is the name of your organization?` | peut rester vide → **Entrée** |
| `What is the name of your City or Locality?` | peut rester vide → **Entrée** |
| `What is the name of your State or Province?` | peut rester vide → **Entrée** |
| `What is the two-letter country code for this unit?` | `FR` → **Entrée** |
| `Is CN=... correct?` | tapez `oui` puis **Entrée** |
| *(si demandé)* `Enter key password for <watermark>` | **Entrée** (utilise le même mot de passe) |

### A.2 🔴 SAUVEGARDER LA CLÉ — L'ÉTAPE LA PLUS IMPORTANTE

Le fichier `nouveau-release.jks` est **désormais la seule chose** qui vous permettra de publier
des mises à jour de votre application. **Si vous le perdez, vous ne pourrez plus jamais mettre à
jour l'app installée sur les téléphones — jamais.**

Copiez-le dans **au moins deux endroits sûrs** (clé USB, disque externe, gestionnaire de mots de
passe) :

```powershell
mkdir "$HOME\Documents\cles-watermark" -Force
copy nouveau-release.jks "$HOME\Documents\cles-watermark\"
```

Et **notez le mot de passe** quelque part de sûr (gestionnaire de mots de passe, papier en lieu sûr).

> ⚠️ Ce fichier ne doit **jamais** aller sur GitHub. Il est déjà ignoré par `.gitignore`, mais
> gardez le réflexe : jamais de `.jks` dans un dépôt.

### A.3 Convertir la clé pour GitHub

GitHub n'accepte pas les fichiers binaires dans les secrets : il faut le convertir en texte.

```powershell
$base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes("nouveau-release.jks"))
Set-Clipboard $base64
Write-Host "✅ Clé copiée dans le presse-papier (long, c'est normal)" -ForegroundColor Green
```

Le texte est maintenant **dans votre presse-papier** : ne le collez pas dans un fichier.
Il va servir tout de suite à l'étape A.4.

### A.4 Mettre les 4 secrets sur GitHub

1. Ouvrez cette page :
   **https://github.com/Souxch06/Watermark_remover/settings/secrets/actions**
2. Cliquez le bouton vert **« New repository secret »**.
3. Créez **exactement** ces 4 secrets, un par un :

| `Name` (le nom) | `Secret` (le contenu) |
|---|---|
| `KEYSTORE_BASE64` | **collez** (Ctrl+V) — le texte de l'étape A.3 |
| `KEYSTORE_PASSWORD` | le mot de passe choisi en A.1 |
| `KEY_ALIAS` | `watermark` |
| `KEY_PASSWORD` | le même mot de passe qu'en A.1 |

> 💡 Pour `KEYSTORE_BASE64` : le contenu est très long (une seule ligne interminable). C'est normal,
> collez-le tel quel.

4. Vérifiez que vous voyez bien **4 secrets** dans la liste.

### A.5 Vérifier que les secrets fonctionnent
**Testez tout de suite**, sans attendre la suite :

1. Allez sur **https://github.com/Souxch06/Watermark_remover/actions**
2. Cliquez sur **« Release »** dans la colonne de gauche.
3. À droite, cliquez **« Run workflow »** → entrez `1.4.0` → **« Run workflow »**.
4. Au bout de 5-10 minutes, l'état doit être **vert ✅**.

S'il est **rouge ❌**, lisez le tableau de l'étape C.1 pour savoir quel secret corriger, puis
relancez. Ne passez pas à l'étape B avant d'avoir du vert ici : cela ne sert à rien de nettoyer
l'historique si la nouvelle clé ne fonctionne pas.

> ℹ️ Ce test crée une vraie release GitHub (`v1.4.0`), c'est normal et sans danger : elle sera
> simplement signée avec votre nouvelle clé. Si vous préférez ne pas publier tout de suite, sautez
> ce test et faites-le à l'étape C.1.

---

## 🧹 Étape B — Nettoyer l'historique Git (10 min)

> **Pourquoi ?** Les fichiers supprimés restent dans l'historique. Il faut réécrire l'historique
> pour qu'ils disparaissent complètement.

### B.1 Installer l'outil de nettoyage

```powershell
pip install git-filter-repo
```

> ❌ Si Windows dit *« pip n'est pas reconnu »* :
> ```powershell
> python -m pip install git-filter-repo
> ```

Vérifiez :

```powershell
git filter-repo --version
```

> ❌ Si *« git filter-repo n'est pas reconnu »* : ajoutez-le au PATH :
> ```powershell
> $env:PATH += ";$HOME\AppData\Roaming\Python\Python311\Scripts"
> git filter-repo --version
> ```
> *(remplacez `Python311` par votre version : lancez `python --version` pour la connaître)*

### B.2 Faire une sauvegarde de sécurité

**Ne sautez pas cette étape.** Le nettoyage réécrit l'historique : en cas de souci, cette copie
vous sauve.

```powershell
cd "$HOME\OneDrive\Documents\VSCODE"
git clone Watermark_remover Watermark_remover_SAUVEGARDE
cd Watermark_remover
Write-Host "✅ Sauvegarde créée" -ForegroundColor Green
```

### B.3 Lancer le nettoyage

```powershell
git filter-repo --sensitive-data-removal --path signing/release.jks --path signing/keystore.properties --invert-paths
```

> ℹ️ **Décryptage de la commande :**
> - `--invert-paths` = « **supprime** ces chemins » (au lieu de ne garder qu'eux), c'est bien ce
>   qu'on veut.
> - `--sensitive-data-removal` = l'option recommandée par l'outil quand il s'agit de données
>   sensibles : il vous guide et vérifie que le nettoyage est complet.

Vous devriez voir passer des lignes `Parsed 20 commits` puis `New history written`.
✅ Si c'est le cas : **la clé a disparu de tout l'historique.**

**Vérifiez-le tout de suite** (remplacez `release.jks` par `keystore.properties` pour le second) :

```powershell
git log --all --name-status -- signing/release.jks
```

Cette commande doit **n'afficher aucune ligne**. Si elle affiche des commits, relancez B.3.

### B.4 Renvoyer sur GitHub

`git filter-repo` retire volontairement le remote `origin` (protection contre une fausse
manipulation). On le remet, puis on pousse :

```powershell
git remote add origin https://github.com/Souxch06/Watermark_remover.git
git push --force --mirror origin
```

> 💡 `--mirror` pousse **toutes** les références (branches + tags) d'un coup : c'est la
> recommandation de la documentation officielle, pour ne rien oublier. Si votre Git le refuse,
> faites-le en deux commandes :
> ```powershell
> git push origin --force --all
> git push origin --force --tags
> ```

> ⚠️ Le `--force` **écrase** l'historique sur GitHub. C'est **exactement** le but ici :
> c'est la seule façon de supprimer définitivement la clé du dépôt.
>
> Si GitHub demande vos identifiants : c'est normal. Utilisez votre nom d'utilisateur GitHub et un
> **token** (pas votre mot de passe) — voir la section *« Le push me demande un mot de passe »* plus bas.

### B.5 Vérifier que la clé a disparu ✅

C'est le moment de vérité. Ouvrez ce lien :

**https://github.com/Souxch06/Watermark_remover/blob/ac1e648/signing/release.jks**

Vous devez voir **« 404 — This is not the file you are looking for »** (page introuvable).

- ✅ **404 = problème résolu.** La clé n'est plus accessible.
- ❌ **Le fichier s'affiche encore** = le nettoyage n'a pas abouti. Vérifiez que B.3 a bien affiché
  `New history written`, puis refaites B.4.

### B.6 Récupérer la dernière version du code

Puisque l'historique a été réécrit, votre dossier local n'est plus « aligné » avec GitHub :

```powershell
git fetch origin
git reset --hard origin/main
git status
```

Vous devez voir `Your branch is up to date with 'origin/main'` et **aucun fichier rouge**.

---

## ✅ Étape C — Vérifier que tout va bien (5 min)

### C.1 Le workflow de publication marche-t-il ?

1. Allez sur **https://github.com/Souxch06/Watermark_remover/actions**
2. Cliquez sur le workflow **« Release »** dans la colonne de gauche.
3. Cliquez sur **« Run workflow »** (à droite) → entrez `1.4.0` → **« Run workflow »**.
4. Attendez 5 à 10 minutes, puis regardez le résultat :

| Ce que vous voyez | Signification | Action |
|---|---|---|
| ✅ **Vert** | Les secrets sont bons, l'APK est signé avec la nouvelle clé | Rien à faire, tout va bien |
| ❌ Rouge, `KEYSTORE_BASE64 ... not configured` | Un secret manque | Refaites A.4 |
| ❌ Rouge, `Invalid version` | La version n'est pas au bon format | Utilisez `1.4.0` (chiffres et points uniquement) |
| ❌ Rouge, `APK is signed with a debug key` | Mauvais secret | Revérifiez `KEYSTORE_BASE64` (A.3/A.4) |

### C.2 Le test « impossible de signer avec la mauvaise clé »

C'est une protection que j'ai ajoutée. Testez-la (facultatif) :

```powershell
cd "$HOME\OneDrive\Documents\VSCODE\Watermark_remover"
.\gradlew.bat assembleRelease
```

Vous devez voir un **message d'erreur clair** disant qu'aucun keystore n'a été trouvé et qu'il
refuse de signer avec la clé de debug. **C'est le comportement voulu** — avant, il publiait
silencieusement un APK inutilisable.

*(Si vous voulez quand même construire un APK de test en local : `.\gradlew.bat assembleDebug`)*

---

## 🆘 Problèmes courants

### « Le push me demande un mot de passe »

GitHub n'accepte plus les mots de passe pour Git. Il faut un **token** :

1. Allez sur **https://github.com/settings/tokens** → **« Generate new token (classic) »**
2. Cochez la case **`repo`** (tout en haut).
3. Cliquez **« Generate token »** en bas, puis **copiez** le token (il commence par `ghp_`).
4. Au moment du push : `Username` = votre nom GitHub, `Password` = **collez le token**.

Pour ne plus le retaper :

```powershell
git config --global credential.helper manager
```

### « filter-repo a supprimé mes commits ! »

Non, il les a **réécrits** (même contenu, sans la clé). Vous pouvez toujours récupérer la copie
faite en B.2 : `Watermark_remover_SAUVEGARDE`.

### « J'ai déjà publié une version avec l'ancienne clé »

Les utilisateurs qui ont installé cette version devront **désinstaller** l'application une fois,
puis réinstaller la nouvelle. C'est inévitable : les nouvelles versions sont signées avec une clé
différente, Android refuse de mettre à jour vers une signature différente.

Prévenez-les dans les notes de version :

```
⚠️ Cette version est signée avec une nouvelle clé de sécurité.
Désinstallez l'ancienne application avant d'installer celle-ci (une seule fois).
Vos vidéos déjà traitées ne sont pas supprimées : elles sont dans la galerie.
```

### « Je veux juste tester le nettoyage sans rien casser »

Faites-le sur la copie de sauvegarde (B.2) : travaillez dans
`Watermark_remover_SAUVEGARDE` au lieu de `Watermark_remover`.

### « git filter-repo refuse : stash not empty / not a fresh clone »

Il exige un dépôt propre. Vérifiez :

```powershell
git status
```

S'il y a des fichiers modifiés, mettez-les de côté :

```powershell
git stash
```

...puis relancez B.3.

---

## 📌 Récapitulatif

| # | Action | Fait |
|---|---|---|
| 1 | Créer une nouvelle clé (`nouveau-release.jks`) | ☐ |
| 2 | **Sauvegarder la clé + le mot de passe** (2 endroits) | ☐ |
| 3 | Mettre les 4 secrets sur GitHub | ☐ |
| 4 | Lancer le workflow **Release** et voir du vert | ☐ |
| 5 | Sauvegarder le dépôt (`Watermark_remover_SAUVEGARDE`) | ☐ |
| 6 | Nettoyer l'historique (`git filter-repo`) | ☐ |
| 7 | Forcer le push (`git push --force --mirror origin`) | ☐ |
| 8 | Vérifier que le lien de la clé renvoie **404** | ☐ |

---

## 💬 Ce que je ne peux pas faire à votre place

- **Je ne connais pas votre mot de passe de clé** : c'est vous qui le choisissez (A.1).
- **Je n'ai pas accès à votre compte GitHub** : les secrets (A.4) et le push (B.4) nécessitent
  votre connexion.
- **Je ne peux pas vérifier l'étape B.7 sans vous** : seul vous pouvez confirmer que la page
  GitHub renvoie bien 404.

Tout le reste du code est déjà corrigé et en ligne. Une fois ce guide terminé, le problème de
sécurité sera **complètement** résolu.

---

*Guide rédigé pour le dépôt `Souxch06/Watermark_remover`. En cas de doute sur une étape, arrêtez-vous
et vérifiez le résultat avant d'enchaîner la suivante.*
