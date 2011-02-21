#!/bin/sh
awk 'BEGIN{c=1}
/<page>/{n++}
n==10000{n=1;c++;close("file"c".xml")}
{print $0 > "file"c".xml"}
'
