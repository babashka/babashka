# Using Babashka with Nix

Babashka is [packaged](https://search.nixos.org/packages?type=packages&query=babashka) in nixpkgs and can be easily used from the Nix package manager.

The following assumes a recent installation of nix and uses the unstable [nix cli](https://nixos.org/manual/nix/stable/command-ref/new-cli/nix.html) and [Flakes](https://nixos.org/manual/nix/stable/command-ref/new-cli/nix3-flake.html).

To enable the unstable cli and flakes add the following to `/etc/nix/nix.conf`:

```
extra-experimental-features flakes nix-command
```

## Imperative install on Nix

To imperatively install nix for the current user, run `nix profile install babashka`.

## Declarative global install on NixOS

To install babashka for all users on a NixOS system, place it in `environment.systemPackages` in your `configuration.nix`:

```nix
{ pkgs, ... }:
{
    environment.systemPackages = with pkgs; [
        babashka
    ];
}
```

Then run `nixos-rebuild switch`, to activate the new configuration.

## Declarative per-user install with home-manager

You can install babashka for a specific user using [home-manager](https://github.com/nix-community/home-manager). Add the following to your `~/.config/nixpkgs/home.nix`:

```nix
{ pkgs, ... }:
{
    home.packages = with pkgs; [
        babashka
    ];
}
```

Then run `home-manager switch`, to activate the new configuration.

## Per project install with direnv

To make babashka available on a per-project basis, you can use [direnv](https://direnv.net/).

Create a file `.envrc` in the project directory with the following contents:

```
use flake
```

Create a file `flake.nix` in the project directory with the following contents:

```nix
{
  outputs = {nixpkgs, ...}: let
    supportedSystems = ["x86_64-linux" "x86_64-darwin"];
    forAllSystems = nixpkgs.lib.genAttrs supportedSystems;
    nixpkgsFor = system: import nixpkgs {inherit system;};
  in {
    devShell = forAllSystems (system: let
      pkgs = nixpkgsFor system;
    in
      pkgs.mkShell {
        packages = with pkgs; [
          babashka
        ];
      });
  };
}
```

After running `direnv allow`, babashka should be available on the `$PATH`, when you are inside the project directory.

## Write Babashka Application

You can write babashka scripts with native dependencies using [WriteBabashkaApplication](https://github.com/sohalt/write-babashka-application).

Here is an example `flake.nix` using `cowsay` as an external dependency:

```nix
{
  inputs.wbba.url = "github:sohalt/write-babashka-application";
  inputs.flake-utils.url = "github:numtide/flake-utils";
  outputs = { nixpkgs, flake-utils, wbba, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          overlays = [ wbba.overlay ];
        };
        hello-babashka = pkgs.writeBabashkaApplication {
          name = "hello";
          runtimeInputs = with pkgs;[
            cowsay # add your dependencies here
          ];
          text = ''
            (ns hello
              (:require [babashka.process :refer [sh]]))

            (-> (sh ["cowsay" "hello from babashka"])
                 :out
                 print)
          '';
        };
      in
      {
        defaultApp = hello-babashka;
        defaultPackage = hello-babashka;
      });
}
```

You can then build the application using `nix build` or run it using `nix run`.
