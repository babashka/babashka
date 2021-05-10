#include <stdio.h>

void recur(int x)
{
  if (x % 10000 == 0)
    printf("%d\n", x);
  recur(x + 1);
}

int main()
{
  recur(0);
  return 0;
}
