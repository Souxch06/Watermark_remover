# 🔍 Audit — est-ce que les fonctionnalités du `.zip` sont vraiment dans l'appli ?

> Réponse courte : **oui pour ce que le `.zip` fait réellement** (les marques de provenance :
> métadonnées C2PA/EXIF/XMP, caractères Unicode invisibles, champs de documents)…
> **et non pour ce qu'il ne fait pas** : le `.zip` **ne retire jamais un filigrane visible**
> (logo, texte incrusté dans l'image). Il ne peut donc pas changer le résultat d'un export vidéo.
>
> Ce document liste tout, ligne par ligne, et donne les commandes pour le vérifier soi-même.

---

## 1. Ce qu'est le `.zip` (et ce qu'il n'est pas)

L'archive `watermarks-remover-main.zip` (branche **`Exemple`**, commit `bcdc964`, SHA-256
`41fd101c3ea19c8064add92e9a7532dc08ee48915a94910cd909f4f0c6648979`) est extraite dans
[`upstream/watermarks-remover-main/`](upstream/watermarks-remover-main/). Son propre README dit :

> *« Agent skill + stdlib Python service to strip **multi-vendor AI provenance marks** from text and
> files »*

Trois étages :

| Étage | Ce qu'il traite | Comment |
|---|---|---|
| **A** | Caractères Unicode invisibles, espaces exotiques, bidi, caractères « tag » | Scripts Python déterministes |
| **B** | Filigranes **statistiques** de texte (le choix des mots) | **Réécriture par un LLM** (best-effort) |
| **Fichiers** | C2PA / EXIF / XMP / propriétés de documents | Chirurgie dans les conteneurs |

Et sa **matrice de couverture** est explicite :

> `Pixel image marks : Out of scope` … `Optional SynthID score + CtrlRegen removal (external)`

Autrement dit : la seule chose qui touche aux **pixels** est un backend **externe à GPU**
(CtrlRegen / DiffusionPurification), pour des filigranes **invisibles**. Un **logo visible**, un
texte incrusté, une bande de watermark : **le `.zip` ne sait pas les enlever**. Ce n'est pas un
outil de retouche, c'est un outil d'hygiène de provenance.

---

## 2. Ce qui a été porté dans l'appli Android (et qui y est)

| Fonction du `.zip` | Dans l'appli ? | Où |
|---|---|---|
| Étage A — Unicode invisible, espaces, bidi, confusables | ✅ | `cleaner/TextUnicodeCleaner.kt` (437 l.) |
| HTML, Markdown, LaTeX, SVG, XML/JSON | ✅ | `NativeWatermarkCleaner.kt` (`cleanHtmlDocument`, `cleanMarkdownDocument`, `cleanLatexDocument`, `cleanSvgDocument`, `cleanXmlDocument`, `cleanJsonDocument`) |
| PNG, JPEG, WebP, GIF, BMP, TIFF | ✅ | chunks `tEXt/zTXt/iTXt`, segments `APPn` (Exif/XMP), RIFF `C2PA`, commentaires, octets de fin |
| AVIF / HEIC / MP4 / MOV | ✅ | boîtes ISO-BMFF `uuid` (C2PA, XMP), `c2pa`, `jumb`, `udta`, `xml `, `meta` |
| WAV, MP3, FLAC | ✅ | blocs RIFF, tag ID3v2 de tête, blocs de métadonnées FLAC |
| DOCX / XLSX / PPTX / ODT / EPUB (archives ZIP) | ✅ | `docProps/*`, métadonnées XMP, XML internes |
| PDF (champs Info + XMP en place) | ⚠️ partiel | les pièces jointes et les images embarquées demandent `qpdf` : l'appli l'indique à l'écran |
| Détection/score SynthID, MarkLLM, stylométrie | ❌ | modèles lourds (Python) |
| CtrlRegen / MarkDiffusion (pixels, GPU) | ❌ | GPU externe, impossible sur téléphone |
| Étage B (réécriture LLM du texte) | ❌ | nécessite un LLM ; l'appli prévient que ce n'est pas embarqué |
| Service HTTP, skill/plugin, hooks pre-commit, audit de site | ❌ | outils de bureau, hors sujet sur Android |

Le portage est **livré depuis la version 1.4.0** (`84a7c82`), élargi en 1.4.1 (`ce2398c`) puis en
1.5.0 (`c657b0c`). Dans l'appli : page d'accueil → bouton **« Nettoyer un fichier »**.

---

## 3. Les preuves (exécutées, pas promises)

Le dépôt contient maintenant un harnais qui compile **les vrais fichiers de l'appli** et lance
**les vrais tests**, sans Android Studio (voir [`verify/README.md`](verify/README.md)) :

```
19 test(s), 0 failure(s)      # RunCleanerTests : le nettoyeur porté (PNG, JPEG, MP4, ZIP, PDF, Unicode…)
28 test(s), 0 failure(s)      # RunCoreTests    : le cœur d'analyse/restauration du filigrane vidéo
```

Et une démonstration lisible, `verify/src/DemoCleaner.kt`, sur des échantillons construits à la main :

| # | Entrée | Sortie |
|---|---|---|
| 1 | Texte avec U+200B, U+FEFF, U+2060, U+200D, espace insécable | 46 → 33 octets, 4 caractères invisibles retirés + espaces normalisés |
| 2 | PNG avec un chunk `tEXt` « c2pa » | 108 → 67 octets, chunk retiré, pixels intacts |
| 3 | MP4 avec une boîte `uuid` C2PA | boîte neutralisée, la marque « c2pa » disparaît |
| 4 | DOCX avec `docProps/core.xml` de provenance | propriétés nettoyées |
| 5 | **MP4 ordinaire sans marque de provenance** | **100 → 100 octets, `modifié=false`** |

---

## 4. Alors pourquoi « toujours le même résultat » ?

Deux cas, tous les deux **normaux** :

1. **Tu regardes le filigrane visible d'une vidéo.** Le `.zip` n'a jamais su les enlever : le
   portage ne touche pas à l'éditeur vidéo, dont le résultat est donc identique. C'est la fonction
   « Choisir une vidéo » (analyse temporelle, inversion, motion fill), pas « Nettoyer un fichier ».
2. **Tu passes un fichier qui ne contient aucune marque de provenance.** L'appli le dit alors
   honnêtement : *« Aucun marqueur trouvé »* — et le fichier de sortie est **identique** à l'entrée
   (cas n° 5 ci-dessus). C'est le comportement correct : sans marque, il n'y a rien à retirer, et
   l'appli refuse de « inventer » une modification.

Depuis la **version 1.6.0**, l'appli le dit explicitement : chaque aide est placée sous son bouton
(« Choisir une vidéo » = filigrane **visible** ; « Nettoyer un fichier » = métadonnées/Unicode
seulement), et l'écran de fin explique, quand rien n'est trouvé, que la sortie est **identique à
l'entrée** — et où retirer un filigrane visible.

---

## 5. Le point à savoir sur le dépôt

`main` **ne contient pas** ce portage : il vit sur la branche
`arena/01a0b0d7-watermark-remover` (release 1.5.0) et, depuis, sur la branche de session
`arena/01a0b56d-watermark-remover`. Tant que la pull request ouverte n'est pas fusionnée dans
`main`, une release déclenchée depuis `main` repartirait sans le nettoyeur.

---

## 6. Vérifier soi-même

**Sur le téléphone** (APK 1.4.0 ou plus récent) : page d'accueil → **« Nettoyer un fichier »** →
choisir une **image exportée par une IA** (elle porte souvent C2PA/EXIF) : le rapport doit lister
les blocs retirés. Le même bouton sur **une vidéo ordinaire** rendra un fichier identique — c'est
attendu.

**Sur un ordinateur** (JDK 17 + `kotlinc`, aucune dépendance Android) :

```bash
bash verify/run-cleaner-tests.sh   # voir verify/README.md pour les deux commandes complètes
```
