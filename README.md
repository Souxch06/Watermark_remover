# Watermark Remover (Android)

[![Android CI](https://github.com/Souxch06/Watermark_remover/actions/workflows/android.yml/badge.svg)](https://github.com/Souxch06/Watermark_remover/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/Souxch06/Watermark_remover?label=APK&logo=android&color=3DDC84)](https://github.com/Souxch06/Watermark_remover/releases/latest)

## 📲 Télécharger l'application

**➡️ [Dernière version (APK)](https://github.com/Souxch06/Watermark_remover/releases/latest)** — ouvrez le lien sur votre
téléphone, téléchargez le fichier `WatermarkRemover-x.y.z.apk`, puis ouvrez-le pour l'installer
(autorisez « Installer des applications inconnues » si Android le demande). Android 7.0 minimum.

Toutes les versions : [page des releases](https://github.com/Souxch06/Watermark_remover/releases).
Chaque `git push` produit aussi un APK de test dans l'onglet **Actions** (artefact « WatermarkRemover-release »).

---

Application Android native (Kotlin + Jetpack Compose + Media3 Transformer) qui supprime un
filigrane d'une vidéo **déjà enregistrée sur l'appareil**. Tout le traitement se fait sur le
téléphone (GPU) : aucune vidéo n'est envoyée sur Internet.

## Fonctionnalités

- **Sélection** via le sélecteur de médias système (aucune permission de stockage globale) ou via
  « Partager » depuis la galerie.
- **Éditeur** : cadre pré-positionné en bas à droite, déplaçable / redimensionnable au doigt,
  jusqu'à 6 zones, guides d'alignement.
- **Aperçu avant / après** rendu par le *même shader* que l'export (fidèle au résultat final),
  bascule Avant/Après + « maintenir pour voir l'original ».
- **4 méthodes** : Reconstruction (recommandée), Flou, Pixellisation, Recadrage. Aucun réglage :
  tout est automatique.
- **Vraie récupération de l'image derrière le filigrane** : l'app analyse une vingtaine d'images
  réparties sur toute la vidéo. Le logo est le seul élément immobile alors que l'image bouge : ses
  gradients sont identiques d'une image à l'autre. En prenant la médiane temporelle des gradients
  puis en l'intégrant (équation de Poisson), on obtient le relief exact du logo (couleur × opacité)
  et un masque au pixel près. Chaque pixel semi-transparent est alors **inversé**
  (`I = (J − a·W) / (1 − a)`) : ce qui apparaît est l'image d'origine, pas une copie.
- **Restauration image par image (`RegionRestorer`)** : pendant l'export, chaque image passe par
  un restaurateur CPU qui (1) mesure si le logo est réellement présent dans l'image (filigranes qui
  changent de place → les images sans logo ne sont pas touchées, plus d'apparitions fugaces),
  (2) inverse le logo, (3) estime le mouvement du fond et **propage l'image restaurée des images
  précédentes** le long de ce mouvement, pondérée par sa fiabilité — ce qui était caché est visible
  quelques images plus tôt ; le bruit amplifié par l'inversion et les traces résiduelles
  disparaissent au fil des images, et une erreur systématique de l'inversion est apprise en ligne
  puis soustraite, (4) comble ce qui reste inconnu (parties opaques, premières images) depuis les
  pixels restaurés voisins. Coût : ~10 ms par image pour une zone de 350×100 px.
  Les vidéos réellement immobiles (rien à récupérer) basculent sur la reconstruction spatiale.
  L'analyse tourne en arrière-plan dans l'éditeur (quelques secondes) et l'aperçu montre le
  résultat de la première image.
- **Aperçu fidèle dans l'éditeur** : l'aperçu « Après » restaure la zone sur le CPU avec le même
  `RegionRestorer` que l'export (aucun état OpenGL en jeu) ; seules les méthodes spatiales passent
  par le shader, et tout échec GL retombe sur l'image d'origine — la vidéo reste toujours visible
  pour placer la zone.
- **Export GPU** (décodage → shader OpenGL → encodage H.264/AAC) avec progression et annulation.
- **Bibliothèque « Mes vidéos »** : toutes les vidéos traitées, avec miniature, durée, résolution,
  taille, méthode utilisée ; lecture, partage, renommage, suppression. Les fichiers sont dans
  `Films/Watermark Remover` (visibles dans la galerie) ; l'index et les miniatures sont stockés en
  privé et synchronisés automatiquement si un fichier est supprimé ailleurs.
- Thème clair / sombre, couleurs dynamiques Material You (Android 12+), FR + EN.

## Architecture

```
app/src/main/java/com/souxch/watermarkremover
├── MainActivity.kt                 point d'entrée, intents "partager / ouvrir avec"
├── model/                          rectangles normalisés, méthodes, géométrie (pur Kotlin, testé)
├── data/
│   ├── ProcessedVideo.kt           entrée de bibliothèque + (dé)sérialisation JSON (testé)
│   └── LibraryRepository.kt        MediaStore + index JSON + miniatures
├── processing/
│   ├── WatermarkShader.kt          shader GLSL (calque récupéré / inpaint / blur / pixelate)
│   ├── WatermarkAnalyzer.kt        analyse temporelle : retrouve le calque du filigrane (Kotlin pur)
│   ├── WatermarkLayer.kt           empaquetage du calque pour le GPU (atlas RGBA)
│   ├── WatermarkLayerBuilder.kt    échantillonne les images de la vidéo et lance l'analyse
│   ├── RegionRestorer.kt           restauration image par image : présence, inversion, propagation temporelle
│   ├── WatermarkRemovalEffect.kt   GlEffect Media3 branché dans le Transformer
│   ├── PreviewRenderer.kt          rendu hors-écran (EGL pbuffer) pour l'aperçu « après »
│   ├── VideoExporter.kt            pipeline Transformer + progression
│   └── VideoRepository.kt          métadonnées et image de prévisualisation
└── ui/
    ├── EditorViewModel.kt          état global, navigation, export, bibliothèque
    ├── WatermarkRemoverApp.kt      Scaffold, onglets Accueil / Mes vidéos, snackbars, back
    ├── components/                 overlay des zones, cartes, badges, helpers
    ├── screens/                    Home, Library, Editor, Exporting / Done / Error
    └── theme/                      palette, formes, typographie
```

## Compiler

Prérequis : Android Studio (Ladybug ou plus récent) ou JDK 17 + Android SDK 35.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/WatermarkRemover-1.0.0-debug.apk
./gradlew assembleRelease      # app/build/outputs/apk/release/WatermarkRemover-1.0.0-release.apk
./gradlew testDebugUnitTest    # tests unitaires (géométrie, sérialisation)
```

Min SDK 24 (Android 7.0), target SDK 35.

## Publier une nouvelle version sur GitHub

Le workflow **Release** (`.github/workflows/release.yml`) compile l'APK et le publie dans
l'onglet *Releases* du dépôt. Deux façons de le déclencher :

```bash
# 1) En poussant un tag de version
git tag v1.0.0
git push origin v1.0.0
```

```
# 2) À la main : onglet Actions → "Release" → "Run workflow" → saisir la version
```

Le `versionCode` Android est incrémenté automatiquement (numéro d'exécution), la release est
créée avec des notes générées, l'APK et son empreinte SHA-256.

### Mises à jour sans désinstaller

Android n'accepte une mise à jour par-dessus une application installée que si les deux sont
signées avec **la même clé**. Toutes les releases (à partir de la 1.4.0) sont donc signées avec la
clé stable `signing/release.jks`, committée dans le dépôt : chaque nouvel APK s'installe
directement par-dessus le précédent, en conservant la bibliothèque et les réglages.

L'application vérifie aussi elle-même (au plus une fois toutes les 6 h, une seule requête vers
l'API GitHub) si une nouvelle release existe et propose de la **télécharger et l'installer en un
appui** depuis l'écran d'accueil.

> Cette clé est publique par nature (le dépôt l'est). Pour passer à une clé privée, ajoutez les
> secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` dans
> *Settings → Secrets and variables → Actions* (ou un `keystore.properties` à la racine en local) :
> ils ont priorité sur la clé committée. Attention : changer de clé oblige les utilisateurs à
> désinstaller une fois.
