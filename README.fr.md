# NBA 2K14 pour Android

Jouer à **NBA 2K14 (next-gen, version PlayStation 4)** hors ligne sur Android, à partir de sa propre copie du jeu.

L'app est un lanceur autonome autour de [shadPS4](https://github.com/shadps4-emu/shadPS4), un émulateur
PlayStation 4, dans sa version ARM64 avec le traducteur x86-64 [FEX](https://github.com/FEX-Emu/FEX). Ni root, ni
Termux, ni autre app : on installe l'APK, on choisit le dossier du jeu, on joue.

*[English version](README.md)*

> **Le jeu n'est pas inclus.** Il faut sa propre copie de NBA 2K14 pour PS4 (CUSA00104), sous forme de dossier de
> jeu décrypté. La version Xbox One n'est pas prise en charge. Ce projet n'est pas affilié à 2K, Visual Concepts ou
> Sony.

## Fonctionnalités

- **Le jeu complet hors ligne** : matchs rapides, MyCAREER et sauvegardes, avec la musique du jeu dans le
  lanceur.
- **Fait pour plusieurs appareils** : un pilote Vulkan par puce graphique (Adreno 6xx à 8xx, Mali en expérimental),
  une cadence selon la puce et l'écran, et un écran **APPAREIL** qui vérifie l'appareil.
- **Manette ou tactile** : manettes Xbox, PlayStation, 8BitDo et intégrées ; sur téléphone, une manette PS4 à
  l'écran, réglable en taille et qui évite l'encoche de la caméra.
- **Attentif à la chaleur** : suit l'état thermique d'Android et passe à 30 images/s tant que l'appareil est trop
  chaud.
- **Des sauvegardes qu'on garde** : export et import en zip ; export des journaux pour signaler un problème.
- Français et anglais.

## Configuration requise

- Android 8.0 ou plus récent, processeur ARM 64 bits
- une puce graphique Qualcomm Adreno 6xx / 7xx / 8xx (Snapdragon), ou ARM Mali Valhall ou plus récente
  (expérimental)
- 12 Go de mémoire conseillés (8 Go au minimum), environ 45 Go d'espace libre

Appareil testé : **AYN Odin 3** (Snapdragon 8 Elite) : 40 images/s, matchs complets. Détails par puce :
[docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).

## Installation

1. Télécharger l'APK dans la [dernière release](../../releases/latest) et l'installer.
2. Copier son dossier NBA 2K14 PS4 décrypté (celui qui contient `eboot.bin` et `sce_sys`) sur l'appareil, par
   exemple dans `Games/`.
3. Pour le son, placer dans ce dossier `libSceNgs2.sprx`, le module audio du logiciel système de sa propre PS4.
4. Ouvrir l'app, choisir le dossier, regarder l'écran **APPAREIL**, puis **JOUER**.

Avant de désinstaller ou de changer d'appareil, exporter ses sauvegardes (**SAUVEGARDES → Exporter les
sauvegardes**) : Android efface les données de l'app avec elle.

## Commandes

| | Manette | Tactile |
|---|---|---|
| Menu de l'app (reprendre, infos, quitter) | touche menu de l'appareil, L3 + R3, ou Retour | **≡** en haut, ou Retour |
| Quitter le jeu | Select + Start maintenus 2 secondes | menu de l'app → Quitter le jeu |
| Changer les touches | menu → COMMANDES | — |
| Taille et transparence des boutons | — | menu de l'app |

## Construire l'app

Voir [docs/BUILDING.md](docs/BUILDING.md) (en anglais), l'architecture dans
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) et les tests dans [docs/TESTING.md](docs/TESTING.md).

## Licence

GPL-2.0 ou ultérieure, comme le moteur sur lequel l'app repose. Voir [LICENSE](LICENSE) et
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

NBA 2K14 est une marque de Take-Two Interactive Software. PlayStation est une marque de Sony Interactive
Entertainment. Ce projet ne contient ni ne distribue aucun de leurs logiciels ou contenus.
