/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.configuration;

import static java.time.Duration.ofDays;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;
import static org.neo4j.configuration.SettingConstraints.max;
import static org.neo4j.configuration.SettingConstraints.min;
import static org.neo4j.configuration.SettingConstraints.range;
import static org.neo4j.configuration.SettingImpl.newBuilder;
import static org.neo4j.configuration.SettingValueParsers.BOOL;
import static org.neo4j.configuration.SettingValueParsers.BYTE;
import static org.neo4j.configuration.SettingValueParsers.BYTES;
import static org.neo4j.configuration.SettingValueParsers.CIDR_IP;
import static org.neo4j.configuration.SettingValueParsers.DOUBLE;
import static org.neo4j.configuration.SettingValueParsers.DURATION;
import static org.neo4j.configuration.SettingValueParsers.INT;
import static org.neo4j.configuration.SettingValueParsers.LONG;
import static org.neo4j.configuration.SettingValueParsers.PATH;
import static org.neo4j.configuration.SettingValueParsers.STRING;
import static org.neo4j.configuration.SettingValueParsers.listOf;
import static org.neo4j.configuration.SettingValueParsers.ofEnum;
import static org.neo4j.configuration.SettingValueParsers.setOf;
import static org.neo4j.io.ByteUnit.kibiBytes;
import static org.neo4j.io.ByteUnit.mebiBytes;

import inet.ipaddr.IPAddressString;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.neo4j.annotations.service.ServiceProvider;
import org.neo4j.graphdb.config.Setting;

@ServiceProvider
public class GraphDatabaseInternalSettings implements SettingsDeclaration {

    // =========================================================================
    // LOAD CSV and apoc.load.json input URI restrictions
    // =========================================================================
    @Internal
    @Description("A list of CIDR-notation IPv4 or IPv6 addresses to block when accessing URLs."
            + "This list is checked when LOAD CSV or apoc.load.json is called.")
    public static final Setting<List<IPAddressString>> cypher_ip_blocklist = newBuilder(
                    "internal.dbms.cypher_ip_blocklist", listOf(CIDR_IP), List.of())
            .build();

    @Internal
    @Description("Path of the databases directory")
    public static final Setting<Path> databases_root_path = newBuilder(
                    "internal.server.directories.databases.root",
                    PATH,
                    Path.of(GraphDatabaseSettings.DEFAULT_DATABASES_ROOT_DIR_NAME))
            .setDependency(GraphDatabaseSettings.data_directory)
            .immutable()
            .build();

    @Internal
    @Description("Enable duplication of user log messages to debug log.")
    public static final Setting<Boolean> duplication_user_messages = newBuilder(
                    "internal.server.logs.user.duplication_to_debug", BOOL, true)
            .immutable()
            .build();

    @Internal
    @Description(
            "Configure lucene partition size. This is mainly used to test partitioning behaviour without having to create "
                    + "Integer.MAX_VALUE indexed entities.")
    public static final Setting<Integer> lucene_max_partition_size =
            newBuilder("internal.dbms.lucene.max_partition_size", INT, null).build();

    @Internal
    @Description("Include additional information in deadlock descriptions.")
    public static final Setting<Boolean> lock_manager_verbose_deadlocks = newBuilder(
                    "internal.dbms.lock_manager.verbose_deadlocks", BOOL, false)
            .dynamic()
            .build();

    @Internal
    @Description("Name of the tracer factory to be used. Current implementations are: null, default & verbose.")
    public static final Setting<String> tracer =
            newBuilder("internal.dbms.tracer", STRING, null).build();

    @Internal
    @Description("Print out the effective Neo4j configuration after startup.")
    public static final Setting<Boolean> dump_configuration =
            newBuilder("internal.dbms.report_configuration", BOOL, false).build();

    @Internal
    @Description(
            "Specifies if the consistency checker should stop when number of observed inconsistencies exceed the threshold. "
                    + "If the value is zero, all inconsistencies will be reported")
    public static final Setting<Integer> consistency_checker_fail_fast_threshold = newBuilder(
                    "internal.consistency_checker.fail_fast_threshold", INT, 0)
            .addConstraint(min(0))
            .build();

    public enum CypherRuntime {
        DEFAULT,
        LEGACY,
        INTERPRETED,
        SLOTTED,
        PIPELINED
    }

    @Internal
    @Description("Set this to specify the default runtime for the default language version.")
    public static final Setting<CypherRuntime> cypher_runtime = newBuilder(
                    "internal.cypher.runtime", ofEnum(CypherRuntime.class), CypherRuntime.DEFAULT)
            .build();

    public enum CypherExpressionEngine {
        DEFAULT,
        INTERPRETED,
        COMPILED,
        ONLY_WHEN_HOT
    }

    @Internal
    @Description(
            "This is used for an optimisation in VarExpandCursor. For paths where the length of the path is below this threshold, "
                    + "the `selectionCursor` will be used to check if a relationship is unique in the path. When the threshold is reached a set will be used instead. "
                    + "If the threshold is set to -1 `selectionCursor` will be used no matter the length of the path.")
    public static final Setting<Integer> var_expand_relationship_id_set_threshold = newBuilder(
                    "internal.cypher.var_expand_relationship_id_set_threshold", INT, 128)
            .addConstraint(min(-1))
            .build();

    @Internal
    @Description("This is used to disable the new shortest path implementation and instead use the old one."
            + "Changing the setting will not affect queries that are cached. So, if you want the switch "
            + "to have immediate effect, you must also call `CALL db.clearQueryCaches()`.")
    public static final Setting<Boolean> use_legacy_shortest_path = newBuilder(
                    "internal.cypher.use_legacy_shortest_path", BOOL, false)
            .dynamic()
            .build();

    @Internal
    @Description("Choose the expression engine. The default is to only compile expressions that are hot, if 'COMPILED' "
            + "is chosen all expressions will be compiled directly and if 'INTERPRETED' is chosen expressions will "
            + "never be compiled.")
    public static final Setting<CypherExpressionEngine> cypher_expression_engine = newBuilder(
                    "internal.cypher.expression_engine",
                    ofEnum(CypherExpressionEngine.class),
                    CypherExpressionEngine.DEFAULT)
            .build();

    @Internal
    @Description("The maximum size in bytes of methods generated for compiled expressions")
    public static final Setting<Integer> cypher_expression_compiled_method_limit = newBuilder(
                    "internal.cypher.expression_method_limit", INT, 8000)
            .addConstraint(min(0))
            .addConstraint(max(65535))
            .build();

    @Internal
    @Description("Number of uses before an expression is considered for compilation")
    public static final Setting<Integer> cypher_expression_recompilation_limit = newBuilder(
                    "internal.cypher.expression_recompilation_limit", INT, 10)
            .addConstraint(min(0))
            .build();

    @Internal
    @Description("Enable tracing of compilation in cypher.")
    public static final Setting<Boolean> cypher_compiler_tracing =
            newBuilder("internal.cypher.compiler_tracing", BOOL, false).build();

    @Internal
    @Description("Large databases might change slowly, and so to prevent queries from never being replanned "
            + "the divergence threshold set by dbms.cypher.statistics_divergence_threshold is configured to "
            + "shrink over time. "
            + "The algorithm used to manage this change is set by internal.cypher.replan_algorithm "
            + "and will cause the threshold to reach the value set here once the time since the previous "
            + "replanning has reached internal.cypher.target_replan_interval. "
            + "Setting this value to higher than the dbms.cypher.statistics_divergence_threshold will cause the "
            + "threshold to not decay over time.")
    public static final Setting<Double> query_statistics_divergence_target = newBuilder(
                    "internal.cypher.statistics_divergence_target", DOUBLE, 0.10)
            .addConstraint(range(0.0, 1.0))
            .build();

    @Internal
    @Description(
            "The threshold when a warning is generated if a label scan is done after a load csv where the label has no index")
    public static final Setting<Long> query_non_indexed_label_warning_threshold = newBuilder(
                    "internal.cypher.non_indexed_label_warning_threshold", LONG, 10000L)
            .build();

    @Internal
    @Description("To improve IDP query planning time, we can restrict the internal planning table size, "
            + "triggering compaction of candidate plans. The smaller the threshold the faster the planning, "
            + "but the higher the risk of sub-optimal plans.")
    public static final Setting<Integer> cypher_idp_solver_table_threshold = newBuilder(
                    "internal.cypher.idp_solver_table_threshold", INT, 128)
            .addConstraint(min(16))
            .build();

    @Internal
    @Description("To improve IDP query planning time, we can restrict the internal planning loop duration, "
            + "triggering more frequent compaction of candidate plans. The smaller the threshold the "
            + "faster the planning, but the higher the risk of sub-optimal plans.")
    public static final Setting<Long> cypher_idp_solver_duration_threshold = newBuilder(
                    "internal.cypher.idp_solver_duration_threshold", LONG, 1000L)
            .addConstraint(min(10L))
            .build();

    @Internal
    @Description("Large databases might change slowly, and to prevent queries from never being replanned "
            + "the divergence threshold set by dbms.cypher.statistics_divergence_threshold is configured to "
            + "shrink over time. The algorithm used to manage this change is set by "
            + "internal.cypher.replan_algorithm and will cause the threshold to reach "
            + "the value set by internal.cypher.statistics_divergence_target once the time since the "
            + "previous replanning has reached the value set here. Setting this value to less than the "
            + "value of dbms.cypher.min_replan_interval will cause the threshold to not decay over time.")
    public static final Setting<Duration> cypher_replan_interval_target = newBuilder(
                    "internal.cypher.target_replan_interval", DURATION, Duration.ofHours(7))
            .build();

    public enum CypherReplanAlgorithm {
        DEFAULT,
        NONE,
        INVERSE,
        EXPONENTIAL
    }

    @Internal
    @Description("Large databases might change slowly, and to prevent queries from never being replanned "
            + "the divergence threshold set by dbms.cypher.statistics_divergence_threshold is configured to "
            + "shrink over time using the algorithm set here. This will cause the threshold to reach "
            + "the value set by internal.cypher.statistics_divergence_target once the time since the "
            + "previous replanning has reached the value set in internal.cypher.target_replan_interval. "
            + "Setting the algorithm to 'none' will cause the threshold to not decay over time.")
    public static final Setting<CypherReplanAlgorithm> cypher_replan_algorithm = newBuilder(
                    "internal.cypher.replan_algorithm",
                    ofEnum(CypherReplanAlgorithm.class),
                    CypherReplanAlgorithm.DEFAULT)
            .build();

    @Internal
    @Description("Set this to enable monitors in the Cypher runtime.")
    public static final Setting<Boolean> cypher_enable_runtime_monitors =
            newBuilder("internal.cypher.enable_runtime_monitors", BOOL, false).build();

    @Internal
    @Description("Enable tracing of pipelined runtime scheduler.")
    public static final Setting<Boolean> enable_pipelined_runtime_trace = newBuilder(
                    "internal.cypher.pipelined.enable_runtime_trace", BOOL, false)
            .build();

    @Internal
    @Description("Path to the pipelined runtime scheduler trace. If 'stdOut' and tracing is on, will print to std out.")
    public static final Setting<Path> pipelined_scheduler_trace_filename = newBuilder(
                    "internal.cypher.pipelined.runtime_trace_path", PATH, Path.of("stdOut"))
            .setDependency(GraphDatabaseSettings.neo4j_home)
            .immutable()
            .build();

    @Internal
    @Description("The size of batches in the pipelined runtime for queries which work with few rows.")
    public static final Setting<Integer> cypher_pipelined_batch_size_small = newBuilder(
                    "internal.cypher.pipelined.batch_size_small", INT, 128)
            .addConstraint(min(1))
            .build();

    @Internal
    @Description("The size of batches in the pipelined runtime for queries which work with many rows.")
    public static final Setting<Integer> cypher_pipelined_batch_size_big = newBuilder(
                    "internal.cypher.pipelined.batch_size_big", INT, 1024)
            .addConstraint(min(1))
            .build();

    @Internal
    @Description(
            "Number of threads to allocate to Cypher worker threads for the parallel runtime. If set to 0, two workers will be started"
                    + " for every physical core in the system. If set to -1, no workers will be started and the parallel runtime cannot be used.")
    public static final Setting<Integer> cypher_worker_count =
            newBuilder("internal.cypher.number_of_workers", INT, 0).build();

    @Internal
    @Description(
            "Maximum number of cached thread execution contexts used by Cypher workers for the parallel runtime. If set to 0, a default value based on"
                    + " `internal.cypher.number_of_workers` will be chosen. If not 0, it has to be greater than `internal.cypher.number_of_workers`.")
    public static final Setting<Integer> cypher_max_cached_worker_resources_count = newBuilder(
                    "internal.cypher.max_number_of_cached_worker_resources", INT, 0)
            .build();

    public enum CypherWorkerManagement {
        DEFAULT,
        THREAD_POOL
    }

    @Internal
    @Description(
            "Enable or disable the parallel runtime. The parallel runtime is an experimental feature and is disabled by default.")
    public static final Setting<CypherParallelRuntimeSupport> cypher_parallel_runtime_support = newBuilder(
                    "internal.cypher.parallel_runtime_support",
                    ofEnum(CypherParallelRuntimeSupport.class),
                    CypherParallelRuntimeSupport.DISABLED)
            .dynamic()
            .build();

    public enum CypherParallelRuntimeSupport {
        DISABLED,
        ALL
    }

    @Internal
    @Description(
            "The worker management determines how the Cypher parallel runtime will distribute query execution work between multiple threads.")
    public static final Setting<CypherWorkerManagement> cypher_worker_management = newBuilder(
                    "internal.cypher.worker_management",
                    ofEnum(CypherWorkerManagement.class),
                    CypherWorkerManagement.DEFAULT)
            .build();

    public enum CypherOperatorEngine {
        DEFAULT,
        COMPILED,
        INTERPRETED
    }

    @Internal
    @Description("If set to true we can force source code generation by appending debug=generate_java_source to query")
    public static final Setting<Boolean> cypher_allow_source_generation = newBuilder(
                    "internal.cypher.pipelined.allow_source_generation", BOOL, false)
            .build();

    @Internal
    @Description(
            "For compiled execution, specialized code is generated and then executed. "
                    + "More optimizations such as operator fusion may apply. "
                    + "Operator fusion means that multiple operators such as for example "
                    + "AllNodesScan -> Filter -> ProduceResult can be compiled into a single specialized operator. "
                    + "This setting only applies to the pipelined and parallel runtime. "
                    + "Allowed values are \"default\" (the default, use compiled when applicable), \"compiled\" and \"interpreted\".")
    public static final Setting<CypherOperatorEngine> cypher_operator_engine = newBuilder(
                    "internal.cypher.pipelined.operator_engine",
                    ofEnum(CypherOperatorEngine.class),
                    CypherOperatorEngine.DEFAULT)
            .build();

    @Internal
    @Description("The maximum size in bytes of methods generated for fused operators")
    public static final Setting<Integer> cypher_operator_compiled_method_limit = newBuilder(
                    "internal.cypher.pipelined.method_limit", INT, 65535)
            .addConstraint(min(0))
            .addConstraint(max(65535))
            .build();

    public enum CypherPipelinedInterpretedPipesFallback {
        DISABLED,
        DEFAULT,
        ALL,
        WHITELISTED_PLANS_ONLY
    }

    @Internal
    @Description(
            "Use interpreted pipes as a fallback for operators that do not have a specialized implementation in the pipelined runtime. "
                    + "Allowed values are \"disabled\", \"default\" (the default, use whitelisted_plans_only when applicable), \"whitelisted_plans_only\" "
                    + "and \"all\" (experimental). "
                    + "The default is to enable the use of a subset of whitelisted operators that are known to be supported, whereas \"all\" is an "
                    + "experimental option that enables the fallback to be used for all possible operators that are not known to be internal.")
    public static final Setting<CypherPipelinedInterpretedPipesFallback> cypher_pipelined_interpreted_pipes_fallback =
            newBuilder(
                            "internal.cypher.pipelined_interpreted_pipes_fallback",
                            ofEnum(CypherPipelinedInterpretedPipesFallback.class),
                            CypherPipelinedInterpretedPipesFallback.DEFAULT)
                    .build();

    @Internal
    @Description(
            "The maximum number of operator fusions over pipelines (i.e. where an operator that would normally be considered pipeline-breaking, "
                    + "e.g. expand), that is considered before a pipeline break is forced.")
    public static final Setting<Integer> cypher_pipelined_operator_fusion_over_pipeline_limit = newBuilder(
                    "internal.cypher.pipelined.operator_fusion_over_pipeline_limit", INT, 8)
            .build();

    public enum SplittingTopBehavior {
        DEFAULT,
        DISALLOW
    }

    @Internal
    @Description(
            "Determines whether the planner is allowed to push down the sort portion of an ORDER BY + LIMIT combination")
    public static final Setting<SplittingTopBehavior> cypher_splitting_top_behavior = newBuilder(
                    "internal.cypher.splitting_top_behavior",
                    ofEnum(SplittingTopBehavior.class),
                    SplittingTopBehavior.DEFAULT)
            .build();

    public enum EagerAnalysisImplementation {
        IR,
        LP
    }

    @Internal
    @Description("Choose the Eager Analysis implementation")
    public static final Setting<EagerAnalysisImplementation> cypher_eager_analysis_implementation = newBuilder(
                    "internal.cypher.eager_analysis_implementation",
                    ofEnum(EagerAnalysisImplementation.class),
                    EagerAnalysisImplementation.IR)
            .build();

    @Internal
    @Description("Enables extra SemanticFeature:s during cypher semantic checking")
    public static final Setting<Set<String>> cypher_enable_extra_semantic_features = newBuilder(
                    "internal.cypher.enable_extra_semantic_features", setOf(STRING), Set.of())
            .build();

    @Internal
    @Description("Max number of recent queries to collect in the data collector module. Will round down to the"
            + " nearest power of two. The default number (8192 query invocations) "
            + " was chosen as a trade-off between getting a useful amount of queries, and not"
            + " wasting too much heap. Even with a buffer full of unique queries, the estimated"
            + " footprint lies in tens of MBs. If the buffer is full of cached queries, the"
            + " retained size was measured to 265 kB. Setting this to 0 will disable data collection"
            + " of queries completely.")
    public static final Setting<Integer> data_collector_max_recent_query_count = newBuilder(
                    "internal.datacollector.max_recent_query_count", INT, 8192)
            .addConstraint(min(0))
            .build();

    @Internal
    @Description("Sets the upper limit for how much of the query text that will be retained by the query collector."
            + " For queries longer than the limit, only a prefix of size limit will be retained by the collector."
            + " Lowering this value will reduce the memory footprint of collected query invocations under loads with"
            + " many queries with long query texts, which could occur for generated queries. The downside is that"
            + " on retrieving queries by `db.stats.retrieve`, queries longer than this max size would be returned"
            + " incomplete. Setting this to 0 will completely drop query texts from the collected queries.")
    public static final Setting<Integer> data_collector_max_query_text_size = newBuilder(
                    "internal.datacollector.max_query_text_size", INT, 10000)
            .addConstraint(min(0))
            .build();

    @Internal
    @Description("Enable or disable the ability to create and drop databases.")
    public static final Setting<Boolean> block_create_drop_database =
            newBuilder("internal.dbms.block_create_drop_database", BOOL, false).build();

    @Internal
    @Description("Enable or disable the ability to start and stop databases.")
    public static final Setting<Boolean> block_start_stop_database =
            newBuilder("internal.dbms.block_start_stop_database", BOOL, false).build();

    @Internal
    @Description("Enable or disable the ability to alter databases.")
    public static final Setting<Boolean> block_alter_database =
            newBuilder("internal.dbms.block_alter_database", BOOL, false).build();

    @Internal
    @Description("Enable or disable the ability to use remote aliases.")
    public static final Setting<Boolean> block_remote_alias =
            newBuilder("internal.dbms.block_remote_alias", BOOL, false).build();

    @Internal
    @Description("Enable or disable the ability to perform server management.")
    public static final Setting<Boolean> block_server_management =
            newBuilder("internal.dbms.block_server_management", BOOL, false).build();

    @Internal
    @Description("Enable or disable the ability to execute the `dbms.upgrade` procedure.")
    public static final Setting<Boolean> block_upgrade_procedures =
            newBuilder("internal.dbms.upgrade_restriction_enabled", BOOL, false).build();

    @Internal
    @Description(
            "The maximum amount of time to wait for the database to become available, when starting a new transaction.")
    public static final Setting<Duration> transaction_start_timeout = newBuilder(
                    "internal.dbms.transaction_start_timeout", DURATION, ofSeconds(1))
            .build();

    @Internal
    @Description("Location of the database scripts directory.")
    public static final Setting<Path> scripts_dir = newBuilder(
                    "internal.server.directories.scripts", PATH, Path.of("scripts"))
            .setDependency(GraphDatabaseSettings.neo4j_home)
            .immutable()
            .build();

    @Internal
    @Description("Name of file containing commands to be run during initialization of the system database. "
            + "The file should exists in the scripts directory in neo4j home directory.")
    public static final Setting<Path> system_init_file = newBuilder("internal.dbms.init_file", PATH, null)
            .immutable()
            .setDependency(scripts_dir)
            .build();

    @Internal
    @Description("Maximum time to wait for active transaction completion when rotating counts store")
    public static final Setting<Duration> counts_store_rotation_timeout = newBuilder(
                    "internal.dbms.counts_store_rotation_timeout", DURATION, ofMinutes(10))
            .build();

    @Internal
    @Description(
            "Set the maximum number of threads that can concurrently be used to sample indexes. Zero means unrestricted.")
    public static final Setting<Integer> index_sampling_parallelism = newBuilder(
                    "internal.dbms.index_sampling.parallelism", INT, 4)
            .addConstraint(min(0))
            .build();

    @Internal
    @Description("Set the maximum number of concurrent index populations across system. "
            + "This also limit the number of threads used to scan store. "
            + "Note that multiple indexes can be populated by a single index population if they were created in the same transaction. "
            + "Zero means unrestricted. ")
    public static final Setting<Integer> index_population_parallelism = newBuilder(
                    "internal.dbms.index_population.parallelism", INT, 2)
            .addConstraint(min(0))
            .build();

    @Internal
    @Description("Set the number of threads used for each index population job. "
            + "Those threads execute individual subtasks provided by index population main threads, see internal.dbms.index_population.parallelism."
            + "Zero means one thread per cpu core. "
            + "Thus the maximum total number of index worker threads in the system is "
            + "internal.dbms.index_population.workers * internal.dbms.index_population.parallelism.")
    public static final Setting<Integer> index_population_workers = newBuilder(
                    "internal.dbms.index_population.workers",
                    INT,
                    Integer.max(1, Runtime.getRuntime().availableProcessors() / 4))
            .addConstraint(min(0))
            .build();

    @Internal
    @Description("If 'true', new database will be created without token indexes for labels and relationships.")
    public static final Setting<Boolean> skip_default_indexes_on_creation = newBuilder(
                    "internal.dbms.index.skip_default_indexes_on_creation", BOOL, false)
            .build();

    @Internal
    @Description("If `true`, Neo4j will abort recovery if any errors are encountered in the logical log. Setting "
            + "this to `false` will allow Neo4j to restore as much as possible from the corrupted log files and ignore "
            + "the rest, but, the integrity of the database might be compromised.")
    public static final Setting<Boolean> fail_on_corrupted_log_files = newBuilder(
                    "internal.dbms.tx_log.fail_on_corrupted_log_files", BOOL, true)
            .build();

    @Internal
    @Description("Specifies if engine should run cypher query based on a snapshot of accessed data. "
            + "Query will be restarted in case if concurrent modification of data will be detected.")
    public static final Setting<Boolean> snapshot_query =
            newBuilder("internal.dbms.query.snapshot", BOOL, false).build();

    @Internal
    @Description(
            "Specifies number or retries that query engine will do to execute query based on stable accessed data snapshot before giving up.")
    public static final Setting<Integer> snapshot_query_retries = newBuilder(
                    "internal.dbms.query.snapshot.retries", INT, 5)
            .addConstraint(range(1, Integer.MAX_VALUE))
            .build();

    @Internal
    @Description("Cypher keeps a cache of the conversion from logical plans to execution plans. "
            + "This cache is mainly meant to avoid generating code multiple times if different queries use the same logical plan. "
            + "Items are only evicted from the cache when all query caches are cleared, e.g. by calling `db.clearQueryCaches()`. "
            + "The cache is allowed to grow up to this size. "
            + "If the size is set to -1 (default), it will use the size configured for the query cache, that is `server.db.query_cache_size`"
            + "Setting the size to 0 means disabling this cache.")
    public static final Setting<Integer> query_execution_plan_cache_size = newBuilder(
                    "internal.dbms.query_execution_plan_cache_size", INT, -1)
            .addConstraint(min(-1))
            .build();

    @Internal
    @Description(
            "Normally, each database has its own query cache, but they are all controlled by the same settings. "
                    + "This means that the space allocated by caches grows by the number of databases although the setting remains untouched. At the same time, this means that one cannot cater well for very different databases, which might use more or less query cache. "
                    + "This setting allows the databases to share the cache size while still being logically separated, which leads to a more dynamic cache allocation per database.")
    public static final Setting<Boolean> enable_unified_query_caches =
            newBuilder("internal.dbms.enable_unified_query_caches", BOOL, false).build();

    /**
     * Block size properties values depends from selected record format.
     * We can't figured out record format until it will be selected by corresponding edition.
     * As soon as we will figure it out properties will be re-evaluated and overwritten, except cases of user
     * defined value.
     */
    @Internal
    @Description("Specifies the block size for storing strings. This parameter is only honored when the store is "
            + "created, otherwise it is ignored. "
            + "Note that each character in a string occupies two bytes, meaning that e.g a block size of 120 will hold "
            + "a 60 character long string before overflowing into a second block. "
            + "Also note that each block carries a ~10B of overhead so record size on disk will be slightly larger "
            + "than the configured block size")
    public static final Setting<Integer> string_block_size = newBuilder("internal.dbms.block_size.strings", INT, 0)
            .addConstraint(min(0))
            .build();

    @Internal
    @Description("Specifies the block size for storing arrays. This parameter is only honored when the store is "
            + "created, otherwise it is ignored. "
            + "Also note that each block carries a ~10B of overhead so record size on disk will be slightly larger "
            + "than the configured block size")
    public static final Setting<Integer> array_block_size = newBuilder(
                    "internal.dbms.block_size.array_properties", INT, 0)
            .addConstraint(min(0))
            .build();

    @Internal
    @Description("Specifies the block size for storing labels exceeding in-lined space in node record. "
            + "This parameter is only honored when the store is created, otherwise it is ignored. "
            + "Also note that each block carries a ~10B of overhead so record size on disk will be slightly larger "
            + "than the configured block size")
    public static final Setting<Integer> label_block_size = newBuilder("internal.dbms.block_size.labels", INT, 0)
            .addConstraint(min(0))
            .build();

    @Internal
    @Description("An identifier that uniquely identifies this graph database instance within this JVM. "
            + "Defaults to an auto-generated number depending on how many instance are started in this JVM.")
    public static final Setting<String> forced_kernel_id = newBuilder("internal.dbms.kernel_id", STRING, null)
            .addConstraint(SettingConstraints.matches("[a-zA-Z0-9]*", "has to be a valid kernel identifier"))
            .build();

    @Internal
    @Description("Enable disable the GC stall monitor.")
    public static final Setting<Boolean> vm_pause_monitor_enabled =
            newBuilder("internal.vm_pause_monitor.enabled", BOOL, true).build();

    @Internal
    @Description("VM pause monitor measurement duration")
    public static final Setting<Duration> vm_pause_monitor_measurement_duration = newBuilder(
                    "internal.vm_pause_monitor.measurement_duration", DURATION, ofMillis(200))
            .build();

    @Internal
    @Description("Alert threshold for total pause time during one VM pause monitor measurement")
    public static final Setting<Duration> vm_pause_monitor_stall_alert_threshold = newBuilder(
                    "internal.vm_pause_monitor.stall_alert_threshold", DURATION, ofMillis(200))
            .build();

    @Internal
    @Description("Create a heap dump just before the end of each query execution. "
            + "The heap dump will be placed in log directory and the file name will contain the query id, to be correlated with an entry in the query log. "
            + "Only live objects will be included to minimize the file size. ")
    public static final Setting<Boolean> log_queries_heap_dump_enabled = newBuilder(
                    "internal.dbms.logs.query.heap_dump_enabled", BOOL, false)
            .dynamic()
            .build();

    @Internal
    @Description("Specifies number of operations that batch inserter will try to group into one batch before "
            + "flushing data into underlying storage.")
    public static final Setting<Integer> batch_inserter_batch_size =
            newBuilder("internal.tools.batch_inserter.batch_size", INT, 10000).build();

    @Internal
    @Description("Location of the auth store repository directory")
    public static final Setting<Path> auth_store_directory = newBuilder(
                    "internal.server.directories.auth", PATH, Path.of("dbms"))
            .immutable()
            .setDependency(GraphDatabaseSettings.data_directory)
            .build();

    @Internal
    @Description("Quiet period for netty shutdown")
    public static final Setting<Integer> netty_server_shutdown_quiet_period = newBuilder(
                    "internal.dbms.bolt.netty_server_shutdown_quiet_period", INT, 5)
            .build();

    @Internal
    @Description("Timeout for netty shutdown")
    public static final Setting<Duration> netty_server_shutdown_timeout = newBuilder(
                    "internal.dbms.bolt.netty_server_shutdown_timeout", DURATION, ofSeconds(15))
            .build();

    @Internal
    @Description("Create an archive of an index before re-creating it if failing to load on startup.")
    public static final Setting<Boolean> archive_failed_index =
            newBuilder("internal.dbms.index.archive_failed", BOOL, false).build();

    @Internal
    @Description("Forces smaller ID cache, in order to preserve memory.")
    public static final Setting<Boolean> force_small_id_cache = newBuilder(
                    "internal.dbms.force_small_id_cache", BOOL, Boolean.FALSE)
            .build();

    @Internal
    @Description("Perform some data consistency checks on transaction apply")
    public static final Setting<Boolean> consistency_check_on_apply = newBuilder(
                    "internal.dbms.storage.consistency_check_on_apply", BOOL, Boolean.FALSE)
            .build();

    @Internal
    @Description("Time interval of inactivity after which a driver will be closed.")
    public static final Setting<Duration> routing_driver_idle_timeout = newBuilder(
                    "internal.dbms.routing.driver.timeout", DURATION, ofMinutes(1))
            .build();

    @Internal
    @Description("Time interval between driver idleness check.")
    public static final Setting<Duration> routing_driver_idle_check_interval = newBuilder(
                    "internal.dbms.routing.driver.idle_check_interval", DURATION, ofMinutes(1))
            .build();

    @Internal
    @Description(
            "Number of event loops used by drivers. Event loops are shared between drivers, so this is the total number of event loops created.")
    @DocumentedDefaultValue("Number of available processors")
    public static final Setting<Integer> routing_driver_event_loop_count = newBuilder(
                    "internal.dbms.routing.driver.event_loop_count",
                    INT,
                    Runtime.getRuntime().availableProcessors())
            .build();

    @Internal
    @Description("Enables logging of leaked driver session")
    public static final Setting<Boolean> routing_driver_log_leaked_sessions = newBuilder(
                    "internal.dbms.routing.driver.logging.leaked_sessions", BOOL, false)
            .build();

    @Internal
    @Description("Specifies the time after which a transaction marked for termination is logged as potentially leaked. "
            + "A value of zero disables the check.")
    public static final Setting<Duration> transaction_termination_timeout = newBuilder(
                    "internal.db.transaction.termination_timeout", DURATION, Duration.ofMinutes(5))
            .dynamic()
            .build();

    @Description("Specifies at which file size the checkpoint log will auto-rotate. Minimum accepted value is 1 KiB. ")
    @Internal
    public static final Setting<Long> checkpoint_logical_log_rotation_threshold = newBuilder(
                    "internal.db.checkpoint_log.rotation.size", BYTES, mebiBytes(1))
            .addConstraint(min(kibiBytes(1)))
            .build();

    @Description("Number of checkpoint logs files to keep.")
    @Internal
    public static final Setting<Integer> checkpoint_logical_log_keep_threshold = newBuilder(
                    "internal.db.checkpoint_log.rotation.keep.files", INT, 3)
            .addConstraint(range(2, 100))
            .build();

    @Internal
    @Description(
            "Whether or not to dump system and database diagnostics. This takes a non-negligible amount of time to do and therefore "
                    + "test databases can disable this to reduce startup times")
    public static final Setting<Boolean> dump_diagnostics =
            newBuilder("internal.dbms.dump_diagnostics", BOOL, Boolean.TRUE).build();

    // === SETTINGS FROM FEATURE TOGGLES ===

    @Internal
    @Description("Validate if transaction statements are properly closed")
    public static final Setting<Boolean> track_tx_statement_close = newBuilder(
                    "internal.dbms.debug.track_tx_statement_close", BOOL, false)
            .build();

    @Internal
    @Description("Trace open/close transaction statements")
    public static final Setting<Boolean> trace_tx_statements =
            newBuilder("internal.dbms.debug.trace_tx_statement", BOOL, false).build();

    @Internal
    @Description("Validate if cursors are properly closed")
    public static final Setting<Boolean> track_cursor_close =
            newBuilder("internal.dbms.debug.track_cursor_close", BOOL, false).build();

    @Internal
    @Description("Trace unclosed cursors")
    public static final Setting<Boolean> trace_cursors =
            newBuilder("internal.dbms.debug.trace_cursors", BOOL, false).build();

    @Internal
    @Description("Reporting interval for page cache speed logging")
    public static final Setting<Duration> page_cache_tracer_speed_reporting_threshold = newBuilder(
                    "internal.dbms.debug.page_cache_tracer_speed_reporting_threshold", DURATION, ofSeconds(10))
            .build();

    @Internal
    @Description("Logging information about recovered index samples")
    public static final Setting<Boolean> log_recover_index_samples = newBuilder(
                    "internal.dbms.index.sampling.log_recovered_samples", BOOL, false)
            .build();

    @Internal
    @Description("Enable asynchronous index sample recovery")
    public static final Setting<Boolean> async_recover_index_samples = newBuilder(
                    "internal.dbms.index.sampling.async_recovery", BOOL, true)
            .immutable()
            .build();

    @Internal
    @Description("Wait for asynchronous index sample recovery to finish")
    public static final Setting<Boolean> async_recover_index_samples_wait = newBuilder(
                    "internal.dbms.index.sampling.async_recovery_wait", BOOL, null)
            .setDependency(async_recover_index_samples)
            .build();

    @Internal
    @Description("Track heap memory allocations for transactions")
    public static final Setting<Boolean> enable_transaction_heap_allocation_tracking = newBuilder(
                    "internal.dbms.enable_transaction_heap_allocation_tracking", BOOL, false)
            .build();

    @Internal
    @Description("Chunk size for heap memory reservation from the memory pool")
    public static final Setting<Long> initial_transaction_heap_grab_size = newBuilder(
                    "internal.dbms.initial_transaction_heap_grab_size", BYTES, mebiBytes(2))
            .build();

    @Internal
    @Description(
            "Default value whether or not to strictly prioritize ids from freelist, as opposed to allocating from high id."
                    + "Given a scenario where there are multiple concurrent calls to allocating IDs"
                    + "and there are free ids on the freelist, some perhaps cached, some not. Thread noticing that there are no free ids cached will try to acquire"
                    + "scanner lock and if it succeeds it will perform a scan and place found free ids in the cache and return. Otherwise:"
                    + "   If `false`: thread will allocate from high id and return, to not block id allocation request."
                    + "   If `true` : thread will await lock released and check cache afterwards. If no id is cached even then it will allocate from high id.")
    public static final Setting<Boolean> strictly_prioritize_id_freelist = newBuilder(
                    "internal.dbms.strictly_prioritize_id_freelist", BOOL, true)
            .build();

    @Internal
    @Description("Block/buffer size for index population")
    public static final Setting<Long> index_populator_block_size = newBuilder(
                    "internal.dbms.index.populator_block_size", BYTES, mebiBytes(1))
            .addConstraint(min(20L))
            .addConstraint(max((long) Integer.MAX_VALUE))
            .build();

    @Internal
    @Description("Merge factory for index population")
    public static final Setting<Integer> index_populator_merge_factor =
            newBuilder("internal.dbms.index.populator_merge_factor", INT, 8).build();

    @Internal
    @Description("Enable/disable logging for the id generator")
    public static final Setting<Boolean> id_generator_log_enabled =
            newBuilder("internal.dbms.idgenerator.log.enabled", BOOL, false).build();

    @Internal
    @Description("Log file rotation threshold for id generator logging")
    public static final Setting<Long> id_generator_log_rotation_threshold = newBuilder(
                    "internal.dbms.idgenerator.log.rotation_threshold", BYTES, mebiBytes(200))
            .build();

    @Internal
    @Description("Log file prune threshold for id generator logging")
    public static final Setting<Duration> id_generator_log_prune_threshold = newBuilder(
                    "internal.dbms.idgenerator.log.prune_threshold", DURATION, ofDays(2))
            .build();

    @Internal
    @Description("Print stack trace on failed native io buffer allocation")
    public static final Setting<Boolean> print_page_buffer_allocation_trace = newBuilder(
                    "internal.dbms.debug.print_page_buffer_allocation_trace", BOOL, false)
            .build();

    @Internal
    @Description("Printing debug information on index population")
    public static final Setting<Boolean> index_population_print_debug = newBuilder(
                    "internal.dbms.index.population_print_debug", BOOL, false)
            .build();

    @Internal
    @Description("Queue size for index population batched updates")
    public static final Setting<Integer> index_population_queue_threshold = newBuilder(
                    "internal.dbms.index.population_queue_threshold", INT, 20_000)
            .build();

    @Internal
    @Description("Max size for an index population batch")
    public static final Setting<Long> index_population_batch_max_byte_size = newBuilder(
                    "internal.dbms.index.population_batch_max_byte_size", BYTES, mebiBytes(10))
            .addConstraint(max((long) Integer.MAX_VALUE))
            .build();

    @Internal
    @Description("Timeout for configuration command evaluation, per command.")
    public static final Setting<Duration> config_command_evaluation_timeout = newBuilder(
                    "internal.dbms.config.command_evaluation_timeout", DURATION, ofSeconds(30))
            .build();

    @Internal
    @Description(
            "Whether or not to do additional checks for locks when making changes as part of commit. This may be expensive to enable.")
    public static final Setting<Boolean> additional_lock_verification =
            newBuilder("internal.dbms.extra_lock_verification", BOOL, false).build();

    @Internal
    @Description("Let the IO controller consider/ignore external IO")
    public static final Setting<Boolean> io_controller_consider_external_io = newBuilder(
                    "internal.dbms.io.controller.consider.external.enabled", BOOL, false)
            .dynamic()
            .build();

    @Internal
    @Description("The maximum number of cached entries in count store (based) stores ")
    public static final Setting<Integer> counts_store_max_cached_entries = newBuilder(
                    "internal.dbms.memory.counts_store_max_cached_entries", INT, 1_000_000)
            .build();

    @Internal
    @Description(
            "Whether or not to use multiple threads whilst performing recovery. Provides performance improvement for some workloads.")
    public static final Setting<Boolean> do_parallel_recovery =
            newBuilder("internal.dbms.recovery.enable_parallelism", BOOL, false).build();

    @Internal
    @Description("Whether or not to log contents of data that is inconsistent when deleting it.")
    public static final Setting<Boolean> log_inconsistent_data_deletion = newBuilder(
                    "internal.dbms.log_inconsistent_data_deletion", BOOL, Boolean.FALSE)
            .dynamic()
            .build();

    @Internal
    @Description("Whether or database should switch to read only mode on disk space problems.")
    public static final Setting<Boolean> dynamic_read_only_failover = newBuilder(
                    "internal.dbms.readonly.failover", BOOL, Boolean.TRUE)
            .dynamic()
            .build();

    @Internal
    @Description("Feature flag to enable/disable planning use of intersection scans.")
    public static final Setting<Boolean> planning_intersection_scans_enabled = newBuilder(
                    "internal.cypher.planning_intersection_scans_enabled", BOOL, true)
            .build();

    @Internal
    @Description("Feature flag to enable/disable planning of unique index seeks for relationships.")
    public static final Setting<Boolean> planning_relationship_unique_index_seek_enabled = newBuilder(
                    "internal.cypher.planning_relationship_unique_index_seek_enabled", BOOL, false)
            .build();

    @Internal
    @Description("Feature flag to enable/disable planning of unique index seeks for relationships under MERGE.")
    public static final Setting<Boolean> planning_merge_relationship_unique_index_seek_enabled = newBuilder(
                    "internal.cypher.planning_merge_relationship_unique_index_seek_enabled", BOOL, false)
            .build();

    @Internal
    @Description(
            "Limits the maximum amount of off-heap memory the consistency checker will allocate. The value is given as a factor between 0.1 .. 1 "
                    + "and will be multiplied with actual available memory to get the effectively available amount of memory taken into consideration")
    public static final Setting<Double> consistency_check_memory_limit_factor = newBuilder(
                    "internal.consistency_checker.memory_limit_factor", DOUBLE, 0.9D)
            .addConstraint(min(0.1D))
            .addConstraint(max(1D))
            .build();

    @Description("Allow database to use dedicated transaction appender writer thread.")
    @Internal
    public static final Setting<Boolean> dedicated_transaction_appender = newBuilder(
                    "internal.dbms.tx.logs.dedicated.appender", BOOL, Boolean.FALSE)
            .build();

    @Internal
    @Description("Enable per page file metrics collection in a default page cache and cursor tracer.")
    public static final Setting<Boolean> per_file_metrics_counters =
            newBuilder("internal.dbms.page.file.tracer", BOOL, false).build();

    @Internal
    @Description("Enables sketching of next transaction log file in the background during reverse recovery.")
    public static final Setting<Boolean> pre_sketch_transaction_logs =
            newBuilder("internal.dbms.tx_log.presketch", BOOL, false).build();

    @Internal
    @Description(
            "Enables using format versions that are still under development, which will trigger migration to them on start up. "
                    + "This setting is only useful for tests of incomplete format versions during their development for for testing upgrade itself.")
    public static final Setting<Boolean> include_versions_under_development =
            newBuilder("internal.dbms.include_dev_format_versions", BOOL, false).build();

    @Internal
    @Description("If set, the database will locate token index files in the old location and under the old name."
            + "This is just a temporary setting to be used when the relocation of these indexes is under development")
    public static final Setting<Boolean> use_old_token_index_location = newBuilder(
                    "internal.dbms.use_old_token_index_location", BOOL, false)
            .build();

    @Description("Whether or not to do parallel index writes during online transaction application")
    @Internal
    public static final Setting<Boolean> parallel_index_updates_apply = newBuilder(
                    "internal.dbms.parallel_index_updates_apply", BOOL, false)
            .build();

    @Description("Whether to offload buffered IDs for freeing to disk, rather than to keep them in memory")
    @Internal
    public static final Setting<Boolean> buffered_ids_offload =
            newBuilder("internal.dbms.id_buffering.offload_to_disk", BOOL, true).build();

    @Internal
    @Description(
            "Max number of processors used when upgrading the store. Defaults to the number of processors available to the JVM. "
                    + "There is a certain amount of minimum threads needed so for that reason there is no lower bound for this "
                    + "value. For optimal performance this value shouldn't be greater than the number of available processors.")
    public static final Setting<Integer> upgrade_processors = newBuilder("internal.dbms.upgrade_max_processors", INT, 0)
            .addConstraint(min(0))
            .dynamic()
            .build();

    @Internal
    @Description(
            "Maximum size after which the planner will not attempt to plan the disjunction of predicates on a single variable as a distinct union."
                    + "For example, given the following pattern: `()-[e:FOO|BAR|BAZ]->()`, the planner will attempt to plan a union of `e:Foo`, `e:Bar`, and `e:Baz`"
                    + "unless `internal.cypher.predicates_as_union_max_size` is less than 3.")
    public static final Setting<Integer> predicates_as_union_max_size = newBuilder(
                    "internal.cypher.predicates_as_union_max_size", INT, 255)
            .addConstraint(min(0))
            .build();

    @Internal
    @Description("A feature toggle behind which uniqueness constraints for relationships are developed")
    public static final Setting<Boolean> rel_unique_constraints = newBuilder(
                    "internal.dbms.relationship_uniqueness_constraints", BOOL, false)
            .build();

    public enum ExtractLiteral {
        ALWAYS,
        NEVER,
        IF_NO_PARAMETER
    }

    @Internal
    @Description("Set this to specify the literal extraction strategy")
    public static final Setting<ExtractLiteral> extract_literals = newBuilder(
                    "internal.cypher.extract_literals", ofEnum(ExtractLiteral.class), ExtractLiteral.ALWAYS)
            .build();

    @Internal
    @Description("Use size of lists and strings of provided parameter values in planning")
    public static final Setting<Boolean> cypher_size_hint_parameters =
            newBuilder("internal.cypher.use_parameter_size", BOOL, false).build();

    @Internal
    @Description(
            "Whether to allow a system graph upgrade to happen automatically in single instance mode (dbms.mode=SINGLE). "
                    + "Default is true. In clustering environments no automatic upgrade will happen (dbms.mode=CORE or dbms.mode=READ_REPLICA). "
                    + "If set to false, or when in a clustering environment, it is necessary to call the procedure `dbms.upgrade()` to "
                    + "complete the upgrade.")
    public static final Setting<Boolean> allow_single_automatic_upgrade = newBuilder(
                    "internal.dbms.allow_single_automatic_upgrade", BOOL, true)
            .dynamic()
            .build();

    @Internal
    @Description("Multi versioned store transaction chunk size.")
    public static final Setting<Long> multi_version_transaction_chunk_size = newBuilder(
                    "internal.db.multiversion.transaction.chunk.size", BYTES, mebiBytes(10))
            .build();

    @Internal
    @Description("Page Cache Warmer blocks database start until it's completed")
    public static final Setting<Boolean> pagecache_warmup_blocking = newBuilder(
                    "internal.db.memory.pagecache.warmup.blocking_enabled", BOOL, false)
            .build();

    @Internal
    @Description("A feature toggle behind which change data capture feature is developed")
    public static final Setting<Boolean> change_data_capture =
            newBuilder("internal.dbms.change_data_capture", BOOL, false).build();

    @Internal
    @Description("A feature toggle behind which show setting feature is developed")
    public static final Setting<Boolean> show_setting =
            newBuilder("internal.dbms.show_setting", BOOL, true).build();

    @Internal
    @Description("A feature toggle behind which out of disk space protection feature is developed")
    public static final Setting<Boolean> out_of_disk_space_protection = newBuilder(
                    "internal.dbms.out_of_disk_space_protection", BOOL, false)
            .build();

    @Internal
    @Description("Just to be used in tests: A way to set the latest dbms runtime component version. "
            + "Can be useful for writing upgrade tests for coming versions")
    public static final Setting<Integer> latest_runtime_version =
            newBuilder("internal.dbms.latest_runtime_version", INT, null).build();

    @Internal
    @Description("Just to be used in tests: A way to set the latest kernel version. "
            + "Can be useful for writing upgrade tests for coming versions")
    public static final Setting<Byte> latest_kernel_version =
            newBuilder("internal.dbms.latest_kernel_version", BYTE, null).build();
}
