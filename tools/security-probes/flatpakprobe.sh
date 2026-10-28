#!/bin/bash

# getting app we are tampering
app_id="org.xfce.mousepad"
if ! [[ -z "$1" ]]; then
    app_id="$1"
fi
echo "Attempting to tamper with \"$app_id\"."

# find .l2s to be able to access other flatpak information
L2S=$(find /data -maxdepth 8 -type d -name .l2s 2>/dev/null | head -1)
echo ".l2s hit: \"$L2S\""

# find files owned by the app, biggest first (usually the app's binary followed by its .desktop or metadata)
# greping over all of .l2s takes a while
hits=$(grep -rl "$app_id" "$L2S" 2>/dev/null | xargs -r ls -S 2>/dev/null)
if [[ -z "$hits" ]]; then
    echo "Could not find a hit for, \"$app_id\", are you sure it's an installed flatpak?"
    exit 1
fi
echo "Top five largest hits owned by \"$app_id\":"
echo "$hits" | head -n 5 | sed 's/^/  /'
hit=$(echo "$hits" | head -1)
echo "Largest hit (the one that will be tampered): \"$hit\""

# optionally save the hit
read -p "Would you like to save the hits to \"hits.txt\" (y/n): " user_input
if [[ "$user_input" == "y" ]]; then
    echo "Saving the hit to \"$PWD/hits.txt\"."
    printf "$hits" > hits.txt
fi

# optionally tamper with the hit
read -p "Would you like to tamper with the hit (y/n): " user_input
if [[ "$user_input" == "y" ]]; then
    printf 'tampered' >> "$hit"
    echo "Successfully tampered a file belonging to, \"$app_id\"."
fi
