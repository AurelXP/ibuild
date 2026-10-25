# ibuild — IA Builder Stick

Mod Fabric (Minecraft 26.3) qui place dans le monde des constructions générées par une IA (fichiers `.json`).
Le prompt à donner à l'IA est dans `prompt_ia.txt`, un exemple dans `exemples/`.

Les builds se mettent dans le dossier `ibuilds/` du dossier du jeu.

## Deux modes

### 1. Avec le mod sur le serveur (ou en solo)
- `/ibuild stick` donne le **Bâton Bâtisseur** (stick jaune). Clic droit sur un bloc = place le build, sneak + clic = build suivant.
- `/ibuild list`, `/ibuild select <nom>`, `/ibuild undo`, `/ibuild reload`.
- Placement direct dans le monde avec effets, gros builds optimisés, undo complet.

### 2. Sans le mod sur le serveur (mode client, nouveauté 1.1.0)
Le mod n'est installé que chez le joueur. Il lit le `.json` sur son PC et l'envoie au serveur sous forme de
commandes `/fill` et `/setblock`.

- Il faut être **opérateur** sur le serveur (ou pouvoir utiliser `/fill` et `/setblock`).
- Prends un **stick vanilla** (`/give @s stick`) : clic droit sur un bloc = place le build, sneak + clic = build suivant.
- Commandes client : `/ibc list`, `/ibc select <nom>`, `/ibc place` (vise un bloc), `/ibc undo`, `/ibc stop`,
  `/ibc speed <1-50>` (commandes par tick, 4 par défaut), `/ibc limit <n>` (taille max d'un `/fill`, 32768 par défaut = gamerule `commandModificationBlockLimit`).
- Limites : les zones du build doivent être chargées par le serveur (un `/fill` échoue sinon) ; pas d'effets de particules ;
  les opérations sont envoyées dans l'ordre du JSON ; `/ibc undo` n'existe que pour les builds de moins de 1,5 million de blocs
  (l'état précédent est lu côté client, donc dans la zone chargée chez le joueur) ; les coffres/panneaux ne sont pas restaurés.
- Si le serveur te déconnecte pour spam ou lag, baisse `/ibc speed`.
