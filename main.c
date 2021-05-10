#include <stdio.h>

void recur(int x)
{
  if (x == 200000) {
    printf("%d\n", x);
    return;
  }
  recur(x + 1);
}

int main()
{
  recur(0);
  return 0;
}
