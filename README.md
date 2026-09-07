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
- **4 méthodes** : Reconstruction (inpainting), Flou, Pixellisation, Recadrage – avec curseurs
  d'intensité et d'adoucissement des bords.
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
│   ├── WatermarkShader.kt          shader GLSL (inpaint / blur / pixelate)
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

### Signature (optionnelle mais recommandée)

Par défaut l'APK est signé avec la clé *debug* : il s'installe sans problème, mais pour que les
futures mises à jour s'installent **par-dessus** l'ancienne version sans la désinstaller, utilisez
une clé stable :

```bash
keytool -genkeypair -v -keystore release.jks -alias watermark -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks      # copier la sortie dans le secret KEYSTORE_BASE64
```

Puis ajoutez dans *Settings → Secrets and variables → Actions* : `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. En local, créez un fichier `keystore.properties`
(ignoré par git) avec `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.
