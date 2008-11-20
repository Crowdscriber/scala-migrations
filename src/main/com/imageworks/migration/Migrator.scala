package com.imageworks.migration

private
case class VersionAndClass(version : Long, clazz : Class[_ <: Migration])

/**
 * A migration to create the schema_migrations table that records
 * which migrations have been applied to a database.
 */
private
class CreateSchemaMigrationsTableMigration
  extends Migration
{
  def up : Unit =
  {
    create_table(Migrator.schema_migrations_table_name) { t =>
      t.varchar("version", Limit(32), NotNull)
    }

    add_index(Migrator.schema_migrations_table_name,
              Array("version"),
              Unique,
              Name("unique_schema_migrations"))
  }

  def down : Unit =
  {
    throw new IrreversibleMigrationException
  }
}

object Migrator
{
  val schema_migrations_table_name = "schema_migrations"

  /**
   * Given a path to a JAR file, return a set of all the names of all
   * the classes the JAR file contains.
   *
   * @param path path to the JAR file
   * @param package_name the package name that the classes should be
   *        in
   * @parm search_sub_packages true if sub-packages of package_name
   *       should be searched
   * @return a set of the class names the JAR file contains
   */
  private
  def class_names_in_jar(path : String,
                         package_name : String,
                         search_sub_packages : Boolean) : scala.collection.mutable.HashSet[String] =
  {
    // Search for the package in the JAR file by mapping the package
    // name to the expected name in the JAR file, then append a '/' to
    // the package name to ensure that no matches are done on
    // different packages that share a common substring.
    val pn = package_name.replace('.', '/') + '/'

    val class_names = new scala.collection.mutable.HashSet[String]
    var jar : java.util.jar.JarFile = null
    try {
      jar = new java.util.jar.JarFile(path, false)
      val entries = jar.entries
      while (entries.hasMoreElements) {
        val name = entries.nextElement.getName
        if (name.startsWith(pn) && name.endsWith(".class")) {
          val class_name = name.substring(0, name.length - ".class".length)
                               .replace('/', '.')
          if (search_sub_packages) {
            class_names += class_name
          }
          else if (! class_name.substring(pn.length).contains('.')) {
            class_names += class_name
          }
        }
      }
      class_names
    }
    finally {
      if (jar ne null) {
        jar.close
      }
    }
  }

  /**
   * Given a java.io.File for a directory containing compiled Java and
   * Scala classes, return a set of all names of the classes the
   * directory contains.
   *
   * @param file the java.io.File corresponding to the directory
   * @param package_name the package name that the classes should be
   *        in
   * @parm search_sub_packages true if sub-packages of package_name
   *       should be searched
   * @return a set of the class names the directory contains
   */
  private
  def class_names_in_dir(file : java.io.File,
                         package_name : String,
                         search_sub_packages : Boolean) : scala.collection.mutable.HashSet[String] =
  {
    val class_names = new scala.collection.mutable.HashSet[String]

    def scan(f : java.io.File,
             pn : String) : Unit =
    {
      val child_files = f.listFiles

      for (child_file <- child_files) {
        if (search_sub_packages && child_file.isDirectory) {
          val child_package_name = if (pn.length > 0)
                                     pn + '.' + child_file.getName
                                   else
                                     child_file.getName
          scan(child_file, child_package_name)
        }
        else if (child_file.isFile) {
          val name = child_file.getName
          if (name.endsWith(".class")) {
            val class_name = pn +
                             '.' +
                             name.substring(0,
                                            name.length - ".class".length)
            class_names += class_name
          }
        }
      }
    }

    scan(file, package_name)

    class_names
  }

  /**
   * Given a Java package name, return a set of concrete classes with
   * a no argument constructor that implement Migration.
   *
   * Limitations:
   * 1) This function assumes that only a single directory or jar file
   *    provides classes in the Java package.
   * 2) It will descend into non-child directories of the package
   *    directory or other jars to find other migrations.
   * 3) It does not support remotely loaded classes and jar files.
   *
   * @param package_name the Java package name to search for Migration
   *        subclasses
   * @parm search_sub_packages true if sub-packages of package_name
   *       should be searched
   * @return a sorted map with verison number keys and the concrete
   *         Migration subclasses as the value
   */
  private
  def find_migrations(package_name : String,
                      search_sub_packages : Boolean) : Array[VersionAndClass] =
  {
    // Ask the current class loader for the resource corresponding to
    // the package, which can refer to a directory, a jar file
    // accessible via the local filesystem or a remotely accessible
    // jar file.  Only the first two are handled.
    val url =
      {
        val pn = package_name.replace('.', '/')
        val u = this.getClass.getClassLoader.getResource(pn)
        if (u eq null) {
          throw new RuntimeException("Cannot find a resource for the '" +
                                     package_name +
                                     "'.")
        }
        u.toString
      }

    val class_names =
      if (url.startsWith("jar:file:")) {
        // This URL ends with a ! character followed by the name of
        // the resource in the jar file, so just get the jar file
        // path.
        val index = url.lastIndexOf('!')
        val path = if (index == -1)
                     url.substring("jar:file:".length)
                   else
                     url.substring("jar:file:".length, index)
        class_names_in_jar(path, package_name, search_sub_packages)
      }
      else if (url.startsWith("file:")) {
        val dir = url.substring("file:".length)
        val file = new java.io.File(dir)
        if (! file.isDirectory) {
          val message = "The resource URL '" +
                        url +
                        "' should be a directory but is not."
          throw new RuntimeException(message)
        }
        class_names_in_dir(file, package_name, search_sub_packages)
      }
      else {
        val message = "Do not know how to get a list of classes in the " +
                      "resource at '" +
                      url +
                      "' corresponding to the package '" +
                      package_name +
                      "'."
        throw new RuntimeException(message)
      }

    // Search through the class names for ones that are concrete
    // subclasses of Migration that have a no argument constructor.
    // Use a sorted map mapping the version to the class name so the
    // final results will be sorted in numerically increasing order.
    var seen_versions = new scala.collection.immutable.TreeMap[Long,String]
    val seen_descriptions = new scala.collection.mutable.HashMap[String,String]

    // Search for classes that have the proper format.
    val re_str = "Migrate_(\\d+)_([_a-zA-Z0-9]*)"
    val re = java.util.regex.Pattern.compile(re_str)

    for (class_name <- class_names) {
      val index = class_name.lastIndexOf('.')
      val base_name = if (index == -1)
                        class_name
                      else
                        class_name.substring(index + 1)
      val matcher = re.matcher(base_name)
      if (matcher.matches) {
        val version_str = matcher.group(1)
        val description = matcher.group(2)
        try {
          val version = java.lang.Long.parseLong(version_str)
          seen_versions.get(version) match {
            case Some(cn) => {
              val message = "The '" +
                            class_name +
                            "' migration defines a duplicate version number " +
                            "with '" +
                            cn +
                            "'."
              throw new DuplicateMigrationVersionException(message)
            }
            case None => {
              seen_versions = seen_versions.insert(version, class_name)
            }
          }

          seen_descriptions.get(description) match {
            case Some(cn) => {
              val message = "The '" +
                            class_name +
                            "' defines a duplicate description with '" +
                            cn +
                            "'."
              throw new DuplicateMigrationDescriptionException(message)
            }
            case None => {
              seen_descriptions.put(description, class_name)
            }
          }
        }
      }
      else {
        class_names -= class_name
        val message = "Skipping '" +
                      class_name +
                      "' because it does not match '" +
                      re_str +
                      "'."
        System.out.println(message)
      }
    }

    val results = new scala.collection.mutable.ArrayBuffer[VersionAndClass] {
                    override
                    def initialSize = seen_versions.size
                  }

    for ((version, class_name) <- seen_versions) {
      var c : Class[_] = null
      try {
        c = Class.forName(class_name)
        if (classOf[Migration].isAssignableFrom(c) &&
            ! c.isInterface &&
            ! java.lang.reflect.Modifier.isAbstract(c.getModifiers)) {
          try {
            // Ensure that there is a no-argument constructor.
            c.getConstructor()
            results += new VersionAndClass(version,
                                           c.asSubclass(classOf[Migration]))
          }
          catch {
            case e : java.lang.NoSuchMethodException => {
              val message = "Unable to find a no-argument constructor for '" +
                            class_name +
                            "': " +
                            e
              System.out.println(e)
            }
          }
        }
      }
      catch {
        case e => {
          val message = "Unable to load class '" +
                        class_name +
                        "'."
          System.out.println(message)
        }
      }
    }

    results.toArray
  }
}

/**
 * This class migrates the database into the desired state.
 */
class Migrator private (jdbc_url : String,
                        jdbc_login : Option[Tuple2[String,String]],
                        adapter : DatabaseAdapter,
                        schema_name_opt : Option[String])
{
  import Migrator._

  /**
   * Construct a migrator to a database that does not need a username
   * and password.
   * @param jdbc_url the JDBC URL to connect to the database
   * @param adapter a concrete DatabaseAdapter that the migrator uses
   *        to handle database specific features
   * @param schema_name_opt an optional schema name used to qualify
   *        all table names in the generated SQL; if Some(), then all
   *        table names are qualified with the name, otherwise, table
   *        names are unqualified
   */
  def this(jdbc_url : String,
           adapter : DatabaseAdapter,
           schema_name_opt : Option[String]) = {
    this(jdbc_url, None, adapter, schema_name_opt)
  }

  /**
   * Construct a migrator to a database that does not need a username
   * and password.
   * @param jdbc_url the JDBC URL to connect to the database
   * @param jdbc_username the username to log into the database
   * @param jdbc_password the password associated with the database
   *        username
   * @param adapter a concrete DatabaseAdapter that the migrator uses
   *        to handle database specific features
   * @param schema_name_opt an optional schema name used to qualify
   *        all table names in the generated SQL; if Some(), then all
   *        table names are qualified with the name, otherwise, table
   *        names are unqualified
   */
  def this(jdbc_url : String,
           jdbc_username : String,
           jdbc_password : String,
           adapter : DatabaseAdapter,
           schema_name_opt : Option[String]) = {
    this(jdbc_url,
         Some((jdbc_username, jdbc_password)),
         adapter,
         schema_name_opt)
  }

  private
  def with_connection[T](f : java.sql.Connection => T) : T =
  {
    val connection =
      jdbc_login match {
        case Some((username, password)) => {
          java.sql.DriverManager.getConnection(jdbc_url, username, password)
        }
        case None => {
          java.sql.DriverManager.getConnection(jdbc_url)
        }
      }

    try {
      f(connection)
    }
    finally {
      connection.close
    }
  }

  /**
   * Get a list of table names in the schema.
   *
   * @return a set of table names; no modifications of the case of
   *         table names is done
   */
  def table_names : scala.collection.Set[String] =
  {
    with_connection { connection =>
      val metadata = connection.getMetaData
      val rs = metadata.getTables(null,
                                  schema_name_opt.getOrElse(null),
                                  null,
                                  Array("TABLE"))

      val names = new scala.collection.mutable.HashSet[String]
      while (rs.next) {
        names += rs.getString(3)
      }
      names.readOnly
    }
  }

  /**
   * Execute a migration in the given direction.
   *
   * @param migration_class the class of migration to execute
   * @param direction the direction the migration should be run
   * @param version_update_opt if provided, the schema_migrations
   *        table is updated using the given connection and migration
   *        version number; this allows this method to
   */
  private
  def run_migration(migration_class : Class[_ <: Migration],
                    direction : MigrationDirection,
                    version_update_opt : Option[Tuple2[java.sql.Connection,Long]]) : Unit =
  {
    val message = "Migrating " +
                  direction.str +
                  " with '" +
                  migration_class.getName +
                  "'."
    System.out.println(message)

    val migration = migration_class.getConstructor().newInstance()
    with_connection { connection =>
      migration.connection = connection
      migration.adapter = adapter
      migration.schema_name_opt = schema_name_opt

      direction match {
        case Up => migration.up
        case Down => migration.down
      }
    }

    version_update_opt match {
      case Some((schema_connection, version)) => {
        val table_name = adapter.quote_table_name(schema_name_opt,
                                                  schema_migrations_table_name)
        val sql =
          direction match {
            case Up => "INSERT INTO " +
                       table_name +
                       " (version) VALUES (?)"
            case Down => "DELETE FROM " +
                         table_name +
                         " WHERE version = ?"
          }

        System.out.println("Executing '" + sql + "'.")

        val statement = schema_connection.prepareStatement(sql)
        statement.setLong(1, version)
        statement.execute()
      }
      case None =>
    }
  }

  /**
   * Creates the schema migrations table if it does not exist.
   */
  private
  def initialize_schema_migrations_table() : Unit =
  {
    val smtn = Migrator.schema_migrations_table_name.toLowerCase
    table_names.find(_.toLowerCase == smtn) match {
      case Some(_) =>
      case None => {
        run_migration(classOf[CreateSchemaMigrationsTableMigration],
                      Up,
                      None)
      }
    }
  }

  /**
   * Get a sorted list of all the installed migrations.
   *
   * @return an array of version numbers of installed migrations
   */
  def get_installed_migrations : Array[Long] =
  {
    with_connection { connection =>
      val sql = "SELECT version FROM " +
                adapter.quote_table_name(schema_name_opt,
                                         schema_migrations_table_name)
      val statement = connection.prepareStatement(sql)
      val rs = statement.executeQuery()
      val versions_list = new scala.collection.mutable.ListBuffer[Long]
      while (rs.next) {
        val version_str = rs.getString(1)
        try {
          val version = java.lang.Long.parseLong(version_str)
          versions_list += version
        }
        catch {
          case e : java.lang.NumberFormatException => {
            val message = "Ignoring install migration with unparsable " +
                          "version number '" +
                          version_str +
                          "'."
            System.out.println(message)
          }
        }
      }

      val versions = versions_list.toArray
      java.util.Arrays.sort(versions)
      versions
    }
  }

  /**
   * Migrate the database.
   *
   * @param package_name the Java package name to search for Migration
   *        subclasses
   * @parm search_sub_packages true if sub-packages of package_name
   *       should be searched
   * @param operation the migration operation that should be performed
   */
  def migrate(operation : MigratorOperation,
              package_name : String,
              search_sub_packages : Boolean) : Unit =
  {
    initialize_schema_migrations_table()

    // Get a new connection that locks the schema_migrations table.
    // This will prevent concurrent migrations from running.
    with_connection { schema_connection =>
      {
        val sql = "LOCK TABLE " +
                  adapter.quote_table_name(schema_name_opt,
                                           schema_migrations_table_name) +
                  " IN EXCLUSIVE MODE"
        System.out.println("Getting an exclusive lock with '" +
                           sql +
                           "'.")
        val statement = schema_connection.prepareStatement(sql)
        statement.execute()
      }

      // Get a list of all available and installed migrations.  Check
      // that all installed migrations have a migration class
      // available to migrate out of that migration.  This can happen
      // if the migration is applied by one copy of an application but
      // another copy does not have that migration, say the migration
      // was not checked into a source control system.  Having a
      // missing migration for an installed migration is not fatal
      // unless the migration needs to be rolled back.
      val installed_migrations = get_installed_migrations
      val available_migrations = find_migrations(package_name,
                                                 search_sub_packages)
      val available_versions = available_migrations.map(_.version)

      for (installed_migration <- installed_migrations) {
        if (! available_versions.contains(installed_migration)) {
          val message = "The migration version '" +
                        installed_migration +
                        "' is installed but there is no migration class " +
                        "available to back it out."
          System.out.println(message)
        }
      }

      if (available_migrations.isEmpty) {
        System.out.println("No migrations found, nothing to do.")
        return
      }

      case class InstallRemove(install_versions : Array[Long],
                               remove_versions : Array[Long])

      // From the operation, determine the migrations to install and
      // the ones to uninstall.
      val install_remove =
        operation match {
          case InstallAllMigrations => {
            new InstallRemove(available_migrations.map(_.version),
                              new Array[Long](0))
          }
          case RemoveAllMigrations => {
            new InstallRemove(new Array[Long](0),
                              installed_migrations.reverse)
          }
          case MigrateToVersion(version) => {
            val index = available_migrations.findIndexOf(_.version == version)
            if (index == -1) {
              val message = "The target version " +
                            version +
                          " does not exist as a migration."
              throw new RuntimeException(message)
            }
            new InstallRemove(available_migrations.take(index + 1).map(_.version).toArray,
                              installed_migrations.filter(_ > version).reverse)
          }
          case RollbackMigration(count) => {
            if (count > installed_migrations.length) {
              val message = "Attempting to rollback " +
                            count +
                            " migrations but the database only has " +
                            installed_migrations.length
                            " installed in it."
              throw new RuntimeException(message)
            }
            new InstallRemove(new Array[Long](0),
                              installed_migrations.reverse.take(count))
          }
        }

      // Always remove migrations before installing new ones.
      for (remove_version <- install_remove.remove_versions) {
        // At the beginning of the method it wasn't a fatal error to
        // have a missing migration class for an installed migration,
        // but when it cannot be removed, it is.
        available_migrations.find(_.version == remove_version) match {
          case Some(version_and_class) => {
            run_migration(version_and_class.clazz,
                          Down,
                          Some((schema_connection, version_and_class.version)))
          }
          case None => {
            val message = "The database has migration version " +
                          remove_version +
                          " installed but there is no migration class " +
                          "available with that version."
            throw new MissingMigrationClass(message)
          }
        }
      }

      for (install_version <- install_remove.install_versions) {
        if (! installed_migrations.contains(install_version)) {
          val vc_opt = available_migrations.find(_.version == install_version)
          run_migration(vc_opt.get.clazz,
                        Up,
                        Some((schema_connection, install_version)))
        }
      }
      null
    }
  }
}
