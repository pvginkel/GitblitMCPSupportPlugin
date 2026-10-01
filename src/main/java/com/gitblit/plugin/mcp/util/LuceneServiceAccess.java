/*
 * Gitblit MCP Support Plugin
 */
package com.gitblit.plugin.mcp.util;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.IndexSearcher;

import com.gitblit.manager.IRepositoryManager;
import com.gitblit.servlet.GitblitContext;
import com.gitblit.service.LuceneService;

/**
 * Reaches into Gitblit's LuceneService, which exposes neither its per-repository
 * index writers nor its searchers. LuceneService sits in a private field of
 * RepositoryManager; its writers map and getIndexSearcher are private too, so
 * all of it is read by reflection.
 */
public final class LuceneServiceAccess {

    private LuceneServiceAccess() {
    }

    public static LuceneService getLuceneService() {
        // Resolves to RepositoryManager, which Gitblit starts before the
        // GitblitManager that also implements IRepositoryManager.
        IRepositoryManager repositoryManager = GitblitContext.getManager(IRepositoryManager.class);
        LuceneService lucene = (LuceneService) readField(repositoryManager, "luceneExecutor");
        if (lucene == null) {
            throw new IllegalStateException("Gitblit's Lucene service is not running");
        }
        return lucene;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, IndexWriter> getWriters(LuceneService lucene) {
        return (Map<String, IndexWriter>) readField(lucene, "writers");
    }

    /**
     * Gitblit's cached searcher for one repository's index, opened (together
     * with its writer) if this is the first use since Gitblit started.
     * Gitblit closes the searcher's reader whenever it indexes new commits,
     * so a caller must hold a reference on the reader while it uses it.
     */
    public static IndexSearcher getIndexSearcher(LuceneService lucene, String repository) throws Exception {
        Method method = LuceneService.class.getDeclaredMethod("getIndexSearcher", String.class);
        method.setAccessible(true);
        try {
            return (IndexSearcher) method.invoke(lucene, repository);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause instanceof Exception ? (Exception) cause : e;
        }
    }

    private static Object readField(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                // Declared further up the hierarchy, if at all
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read " + type.getName() + "." + name, e);
            }
        }
        throw new IllegalStateException("No field " + name + " on " + target.getClass().getName());
    }
}
