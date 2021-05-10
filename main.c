#include <stdio.h>

void printer(int x)
{
  printf("%d\n", x);
  printer(x + 1);
}

int main()
{
  printer(0);
  return 0;
}
