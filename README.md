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
- **Aucun texte fantôme flou** : les bords anti-aliasés du filigrane (ou adoucis par la compression
  de la vidéo) forment une rampe d'opacité trop faible pour l'analyse par gradients ; cette rampe
  est ré-estimée directement depuis les données (médiane temporelle projetée sur la couleur du
  logo, dedans comme dehors du masque), ce qui supprime le halo pâle qui subsistait autour des
  glyphes — et l'anneau d'un logo aux bords nets n'est plus sur-inversé.
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
- **Interface Material 3 épurée** : palette indigo → violet → bleu ciel identique à l'icône
  (mode clair et sombre), cartes blanches à liseré fin, tuiles de méthode, bascule Avant / Après
  flottante sur l'aperçu, bouton d'export épinglé en bas de l'éditeur, écrans d'attente / de
  succès / d'erreur harmonisés. Icône adaptative vectorielle (cadre vidéo dont le coin est effacé
  par une étincelle) + PNG hérités pour Android 7.
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

