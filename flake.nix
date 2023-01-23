{
  description = "A snowflake for our girl";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-22.11";

  outputs = { self, nixpkgs }: {
    devShells.x86_64-linux.default =
      let
        pkgs = import nixpkgs {
          system = "x86_64-linux";
        };
      in
        pkgs.mkShell {
          packages = with pkgs; [
            clojure
            leiningen
          ];
        };
  };
}
