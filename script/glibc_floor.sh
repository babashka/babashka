# Single definition of the glibc floor of the dynamic linux binaries: the
# highest glibc symbol version bb may bind. Checked pre build by
# script/check_glibc.sh with a link probe and post build by
# script/verify_link on the binary. The install script mirrors this value
# in min_glibc_version; verify_link fails on drift.

glibc_floor="2.28"

# prints the highest GLIBC_x.y symbol version a binary references
max_glibc_symbol() {
    objdump -T "$1" | grep -oE 'GLIBC_[0-9]+\.[0-9]+(\.[0-9]+)?' | sed 's/GLIBC_//' | sort -uV | tail -1
}
