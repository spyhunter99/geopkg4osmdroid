/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.osmdroid.geopackagetoosm;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import mil.nga.geopackage.BoundingBox;
import mil.nga.geopackage.GeoPackage;
import mil.nga.geopackage.io.TileFormatType;
import mil.nga.geopackage.io.TileProperties;
import mil.nga.geopackage.io.TileWriter;
import mil.nga.geopackage.manager.GeoPackageManager;
import mil.nga.geopackage.projection.Projection;
import mil.nga.geopackage.projection.ProjectionConstants;
import mil.nga.geopackage.projection.ProjectionFactory;
import mil.nga.geopackage.projection.ProjectionTransform;
import mil.nga.geopackage.tiles.ImageUtils;
import mil.nga.geopackage.tiles.TileBoundingBoxUtils;
import mil.nga.geopackage.tiles.TileDraw;
import mil.nga.geopackage.tiles.TileGrid;
import mil.nga.geopackage.tiles.matrix.TileMatrix;
import mil.nga.geopackage.tiles.matrixset.TileMatrixSet;
import mil.nga.geopackage.tiles.user.TileDao;
import mil.nga.geopackage.tiles.user.TileRow;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;

/**
 * This is based off of NGA's Geopackage TileWriter class. Modified to be a bit more use friendly and to write
 * output files into OSMDroid compatible sqlite databases
 * @author alex
 */
public class Main {

     /**
      * Argument prefix
      */
     public static final String ARGUMENT_PREFIX = "-";

     /**
      * Tile Type argument
      */
     public static final String ARGUMENT_TILE_TYPE = "t";

     /**
      * Image Format argument
      */
     public static final String ARGUMENT_IMAGE_FORMAT = "i";

     /**
      * Raw image argument
      */
     public static final String ARGUMENT_RAW_IMAGE = "r";

     /**
      * Default tile type
      */
     public static final TileFormatType DEFAULT_TILE_TYPE = TileFormatType.STANDARD;

     /**
      * Default image format
      */
     public static final String DEFAULT_IMAGE_FORMAT = ImageUtils.IMAGE_FORMAT_PNG;

     /**
      * Logger
      */
     private static final Logger LOGGER = Logger.getLogger(TileWriter.class
          .getName());

     /**
      * Progress log frequency within a zoom level
      */
     private static final int ZOOM_PROGRESS_FREQUENCY = 100;

     static Connection con;

     /**
      * Main method to write tiles from a GeoPackage
      *
      * @param args
      */
     public static void main(String[] args) throws Exception {

          TileFormatType tileType = null;
          String imageFormat = null;
          boolean rawImage = false;
          File geoPackageFile = null;
          String tileTable = null;
          String outputFile = "";

          Options opts = new Options();
          opts.addOption("t", true, "geopackage - x and y represent GeoPackage Tile Matrix width and height, "
               + "standard - x and y origin is top left (Google format),"
               + "tms - (Tile Map Service) x and y origin is bottom left");
          opts.addOption("i", true, "Output image format: png, jpg, jpeg (default is 'jpg')");
          opts.addOption("raw", false, "Use the raw image bytes, only works when combining and cropping is not required");
          opts.addOption("table", true, "the tile table to export");
          opts.addOption("input", true, "geopackage_file");
          opts.addOption("output", true, "output database file");
          opts.addOption("list", true, "(geopackage), lists all tile tables");
          opts.addOption("shift", false, "interactive z x y into an osm key");
          opts.addOption("help", false, "help");

          CommandLineParser parser = new DefaultParser();
          CommandLine parse = parser.parse(opts, args);
          if (parse.hasOption("help")) {
               HelpFormatter formatter = new HelpFormatter();
               formatter.printHelp("geopackageToOsm", opts);
               return;
          }
          if (parse.hasOption("list")) {
               GeoPackage geoPackage = GeoPackageManager.open(new File(parse.getOptionValue("list")));
               Iterator<String> iterator = geoPackage.getTileTables().iterator();
               while (iterator.hasNext()) {
                    System.out.println(iterator.next());
               }
               return;
          }
          if (parse.hasOption("shift")) {
               System.out.print("Z = ");
               int z = Integer.parseInt(System.console().readLine());
               System.out.print("Y = ");
               int y = Integer.parseInt(System.console().readLine());
               System.out.print("X = ");
               int x = Integer.parseInt(System.console().readLine());
               long key = ((z << z) + x << z) + y;
               System.out.println(key);
               return;
          }

          if (parse.hasOption("t")) {
               tileType = TileFormatType.valueOf(parse.getOptionValue("t").toUpperCase());
          } else {
               tileType = TileFormatType.STANDARD;
          }
          if (parse.hasOption("i")) {
               imageFormat = (parse.getOptionValue("i"));
          } else {
               imageFormat = "jpg";
          }
          if (parse.hasOption("raw")) {
               rawImage = true;
          }
          if (parse.hasOption("output")) {
               outputFile = parse.getOptionValue("output");
          } else {
               outputFile = "out.sqlite";
          }

          con = DriverManager.getConnection("jdbc:sqlite:output.db");
          try {
               Statement stmt = con.createStatement();
               stmt.executeUpdate("CREATE TABLE tiles (key INTEGER PRIMARY KEY, provider TEXT, tile BLOB);");
               stmt.close();
          } catch (Exception ex) {
          }

          geoPackageFile = new File(parse.getOptionValue("input"));
          if (!geoPackageFile.exists()) {
               System.err.println(geoPackageFile.getAbsolutePath() + " does not exist!");
               return;
          }
          //outputDirectory = new File(parse.getOptionValue("output"));
          tileTable = parse.getOptionValue("table");

          // Write the tiles
          try {
               writeTiles(geoPackageFile, tileTable, null,imageFormat, tileType, rawImage);
          } catch (Exception e) {
               throw e;
          }
          con.close();

     }

     /**
      * Write the tile table tile image set within the GeoPackage file to the
      * provided directory
      *
      * @param geoPackageFile GeoPackage file
      * @param tileTable tile table
      * @param directory output directory
      * @param imageFormat image format
      * @param tileType tile type
      * @param rawImage use raw image flag
      * @throws IOException
      */
     public static void writeTiles(File geoPackageFile, String tileTable,
          File directory, String imageFormat, TileFormatType tileType,
          boolean rawImage) throws Exception {

          GeoPackage geoPackage = GeoPackageManager.open(geoPackageFile);
          try {
               writeTiles(geoPackage, tileTable, directory, imageFormat, tileType,
                    rawImage);
          } finally {
               geoPackage.close();
          }
     }

     /**
      * Write the tile table tile image set within the GeoPackage file to the
      * provided directory
      *
      * @param geoPackageFile open GeoPackage
      * @param tileTable tile table
      * @param directory output directory
      * @param imageFormat image format
      * @param tileType tile type
      * @param rawImage use raw image flag
      * @throws IOException
      */
     public static void writeTiles(GeoPackage geoPackage, String tileTable,
          File directory, String imageFormat, TileFormatType tileType,
          boolean rawImage) throws Exception {

          // Get a tile data access object for the tile table
          TileDao tileDao = geoPackage.getTileDao(tileTable);

          // If no format, use the default
          if (imageFormat == null) {
               imageFormat = DEFAULT_IMAGE_FORMAT;
          }

          // If no tiles type, use the default
          if (tileType == null) {
               tileType = DEFAULT_TILE_TYPE;
          }

          LOGGER.log(Level.INFO, "GeoPackage: " + geoPackage.getName()
               + ", Tile Table: " + tileTable + ", Output Directory: "
               + directory + (rawImage ? ", Raw Images" : "")
               + ", Image Format: " + imageFormat + ", Tiles Type: "
               + tileType + ", Zoom Range: " + tileDao.getMinZoom() + " - "
               + tileDao.getMaxZoom());

          int totalCount = 0;

          // Go through each zoom level
          for (long zoomLevel = tileDao.getMinZoom(); zoomLevel <= tileDao
               .getMaxZoom(); zoomLevel++) {

               // Get the tile matrix at this zoom level
               TileMatrix tileMatrix = tileDao.getTileMatrix(zoomLevel);

               LOGGER.log(
                    Level.INFO,
                    "Zoom Level: "
                    + zoomLevel
                    + ", Width: "
                    + tileMatrix.getMatrixWidth()
                    + ", Height: "
                    + tileMatrix.getMatrixHeight()
                    + ", Max Tiles: "
                    + (tileMatrix.getMatrixWidth() * tileMatrix
                    .getMatrixHeight()));

               //File zDirectory = new File(directory, String.valueOf(zoomLevel));
               int zoomCount = 0;
               switch (tileType) {

                    case GEOPACKAGE:
                         zoomCount = writeGeoPackageFormatTiles(tileDao, zoomLevel,
                              tileMatrix, null, imageFormat, rawImage);
                         break;

                    case STANDARD:
                    case TMS:
                         zoomCount = writeFormatTiles(tileDao, zoomLevel, tileMatrix,
                              null, imageFormat, tileType, rawImage, tileTable);
                         break;

                    default:
                         throw new UnsupportedOperationException(
                              "Tile Type Not Supported: " + tileType);
               }

               LOGGER.log(Level.INFO, "Zoom " + zoomLevel + " Tiles: " + zoomCount);

               totalCount += zoomCount;
          }

          // If GeoPackage format, write a properties file
          if (tileType == TileFormatType.GEOPACKAGE) {
               tileDao = geoPackage.getTileDao(tileTable);
               TileProperties tileProperties = new TileProperties(directory);
               tileProperties.writeFile(tileDao);
          }

          LOGGER.log(Level.INFO, "Total Tiles: " + totalCount);
     }

     /**
      * Write GeoPackage formatted tiles
      *
      * @param tileDao
      * @param zoomLevel
      * @param tileMatrix
      * @param zDirectory
      * @param imageFormat
      * @param rawImage
      * @return
      * @throws IOException
      */
     private static int writeGeoPackageFormatTiles(TileDao tileDao,
          long zoomLevel, TileMatrix tileMatrix, File zDirectory,
          String imageFormat, boolean rawImage) throws IOException {

          int tileCount = 0;

          // Go through each x in the width
          for (int x = 0; x < tileMatrix.getMatrixWidth(); x++) {

               File xDirectory = new File(zDirectory, String.valueOf(x));

               // Go through each y in the height
               for (int y = 0; y < tileMatrix.getMatrixHeight(); y++) {

                    // Query for a tile at the x, y, z
                    TileRow tileRow = tileDao.queryForTile(x, y, zoomLevel);

                    if (tileRow != null) {

                         // Get the image bytes
                         byte[] tileData = tileRow.getTileData();

                         if (tileData != null) {

                              // Make any needed directories for the image
                              xDirectory.mkdirs();

                              File imageFile = new File(xDirectory, String.valueOf(y)
                                   + "." + imageFormat);

                              if (rawImage) {

                                   // Write the raw image bytes to the file
                                   FileOutputStream fos = new FileOutputStream(
                                        imageFile);
                                   fos.write(tileData);
                                   fos.close();

                              } else {

                                   // Read the tile image
                                   BufferedImage tileImage = tileRow
                                        .getTileDataImage();

                                   // Create the new image in the image format
                                   BufferedImage image = ImageUtils
                                        .createBufferedImage(tileImage.getWidth(),
                                             tileImage.getHeight(), imageFormat);
                                   Graphics graphics = image.getGraphics();

                                   // Draw the image
                                   graphics.drawImage(tileImage, 0, 0, null);

                                   // Write the image to the file
                                   ImageIO.write(image, imageFormat, imageFile);
                              }

                              tileCount++;

                              if (tileCount % ZOOM_PROGRESS_FREQUENCY == 0) {
                                   LOGGER.log(Level.INFO, "Zoom " + zoomLevel
                                        + " Tile Progress... " + tileCount);
                              }
                         }
                    }
               }
          }

          return tileCount;
     }

     /**
      * Write formatted tiles
      *
      * @param tileDao
      * @param zoomLevel
      * @param tileMatrix
      * @param zDirectory
      * @param imageFormat
      * @param tileType
      * @param rawImage
      * @return
      * @throws IOException
      */
     private static int writeFormatTiles(TileDao tileDao, long zoomLevel,
          TileMatrix tileMatrix, File zDirectory, String imageFormat,
          TileFormatType tileType, boolean rawImage, String layer) throws IOException, SQLException {

          int tileCount = 0;

          // Get the projection of the tile matrix set
          long epsg = tileDao.getTileMatrixSet().getSrs()
               .getOrganizationCoordsysId();
          Projection projection = ProjectionFactory.getProjection(epsg);

          // Get the transformation to web mercator
          Projection webMercator = ProjectionFactory
               .getProjection(ProjectionConstants.EPSG_WEB_MERCATOR);
          ProjectionTransform projectionToWebMercator = projection
               .getTransformation(webMercator);

          // Get the tile matrix set and bounding box
          TileMatrixSet tileMatrixSet = tileDao.getTileMatrixSet();
          BoundingBox setProjectionBoundingBox = tileMatrixSet.getBoundingBox();
          BoundingBox setWebMercatorBoundingBox = projectionToWebMercator
               .transform(setProjectionBoundingBox);

          // Determine the tile grid in the world the tiles cover
          TileGrid tileGrid = TileBoundingBoxUtils.getTileGrid(
               setWebMercatorBoundingBox, (int) zoomLevel);

          // Go through each tile in the tile grid
          for (long x = tileGrid.getMinX(); x <= tileGrid.getMaxX(); x++) {

               // Build the z/x directory
               //File xDirectory = new File(zDirectory, String.valueOf(x));
               for (long y = tileGrid.getMinY(); y <= tileGrid.getMaxY(); y++) {

                    // Get the y file name for the specified format
                    long yFileName = y;
                    if (tileType == TileFormatType.TMS) {
                         yFileName = TileBoundingBoxUtils.getYAsOppositeTileFormat(
                              (int) zoomLevel, (int) y);
                    }

                    //File imageFile = new File(xDirectory, String.valueOf(yFileName)
                    //   + "." + imageFormat);
                    TileRow tileRow = null;
                    BufferedImage image = null;
                    if (rawImage) {
                         tileRow = TileDraw.getRawTileRow(tileDao, tileMatrix,
                              setWebMercatorBoundingBox, x, y, zoomLevel);
                    } else {
                         // Create the buffered image
                         image = TileDraw.drawTile(tileDao, tileMatrix, imageFormat,
                              setWebMercatorBoundingBox, x, y, zoomLevel);
                    }

                    if (tileRow != null || image != null) {

                         // Make any needed directories for the image
                         // xDirectory.mkdirs();
                         //((z << z) + x << z) + y
                         long key = ((zoomLevel << zoomLevel) + x << zoomLevel) + y;

                         if (rawImage) {
                              // Write the raw image bytes to the file
                              PreparedStatement prepareStatement = con.prepareStatement("insert into tiles (key,provider, tile) values (?,?,?);");
                              prepareStatement.setLong(1, key);
                              prepareStatement.setString(2, layer);
                              prepareStatement.setBytes(3, tileRow.getTileData());
                              prepareStatement.execute();
                              prepareStatement.close();

                              //FileOutputStream fos = new FileOutputStream(imageFile);
                              //fos.write(tileRow.getTileData());
                              //fos.close();
                         } else {
                              // Write the image to the file
                              ByteArrayOutputStream baos = new ByteArrayOutputStream();

                              ImageIO.write(image, imageFormat, baos);
                              //ImageIO.write(image, imageFormat, imageFile);
                              baos.flush();
                              byte[] imageInByte = baos.toByteArray();
                              baos.close();
                              //X/Y/Z 

                              PreparedStatement prepareStatement = con.prepareStatement("insert into tiles (key,provider, tile) values (?,?,?);");
                              prepareStatement.setLong(1, key);
                              prepareStatement.setString(2, layer);
                              prepareStatement.setBytes(3, imageInByte);
                              prepareStatement.execute();
                              prepareStatement.close();
                         }

                         tileCount++;

                         if (tileCount % ZOOM_PROGRESS_FREQUENCY == 0) {
                              LOGGER.log(Level.INFO, "Zoom " + zoomLevel
                                   + " Tile Progress... " + tileCount);
                         }
                    }

               }
          }

          return tileCount;
     }

}
