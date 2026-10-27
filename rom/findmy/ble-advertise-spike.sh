#!/system/bin/sh
# Feasibility: drive the BT controller directly over /dev/stpbt (Android Bluetooth must be off) and start LE advertising.
LOG=/data/local/tmp/hci.log
rm -f $LOG
exec 3<>/dev/stpbt || { echo "open failed"; exit 1; }
cat <&3 > $LOG &
DRAIN=$!
sleep 1
printf '\001\003\014\000' >&3                       # HCI_Reset
sleep 1
printf '\001\001\020\000' >&3                       # Read Local Version
sleep 1
printf '\001\003\020\000' >&3                       # Read Local Supported Features (bit 38 = LE supported)
sleep 1
# LE Set Advertising Parameters: 1 s interval, ADV_NONCONN_IND, public addr, all channels
printf '\001\006\040\017\100\006\100\006\003\000\000\000\000\000\000\000\000\007\000' >&3
sleep 1
# LE Set Advertising Data: flags + manufacturer data 0xFFFF "Y1!!"
printf '\001\010\040\040\012\002\001\006\006\377\377\377\131\061\041\000\000\000\000\000\000\000\000\000\000\000\000\000\000\000\000\000\000\000\000\000' >&3
sleep 1
printf '\001\012\040\001\001' >&3                   # LE Set Advertise Enable
sleep ${1:-20}
printf '\001\012\040\001\000' >&3                   # advertise off
sleep 1
kill $DRAIN 2>/dev/null
exec 3>&-
od -An -tx1 $LOG | head -20
