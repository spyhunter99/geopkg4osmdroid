# geopkg4osmdroid
Geopack support for OSMDroid by way of conversion to osmdroid's sqlite format (for performance reasons)

# Compile
```mvn clean install ```

# Run

## Help

``` geopackageToOsm-<VERSION>-jar-with-dependencies.jar -help ```

## List tile tables

``` geopackageToOsm-<VERSION>-jar-with-dependencies.jar -list <INPUT>.gpkg ```

## Convert GeoPackage to OSMDroid sqlite database

``` geopackageToOsm-<VERSION>-jar-with-dependencies.jar -t standard -input <INPUT>.gpkg -t standard -table <TABLE> -i jpg -o <OUTPUT.sqlite>```
