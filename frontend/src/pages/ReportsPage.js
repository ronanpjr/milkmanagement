import React, { useState, useEffect, useMemo, useCallback } from 'react';
import {
  LineChart, Line, PieChart, Pie, Cell, AreaChart, Area, BarChart, Bar, ScatterChart, Scatter,
  ComposedChart, ReferenceLine,
  XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer
} from 'recharts';
import MultiSelect from '../components/MultiSelect';
import ChartAoRolar from '../components/ChartAoRolar';
import { chaveDataISO, dataBRDeDataISO, chaveMesDeDataISO, chaveSemanaDeDataISO } from '../utils/calendar';
import { idTexto } from '../utils/ids';

// Paleta dos gráficos. No escopo do módulo (e não dentro do componente) para
// ter identidade estável entre renders e poder entrar nas dependências do
// useMemo que a consome.
const COLORS = ['#0088FE', '#00C49F', '#FFBB28', '#FF8042', '#AF19FF', '#FF1919'];
const COR_OUTROS = '#9ca3af';

// Altura única de todos os gráficos da página. Antes a altura misturava 300px
// (primeira fileira e qualidade) e 256px (`h-64`, demais seções): as linhas da
// grade ficavam desalinhadas e o "espaço vazio" mudava de seção para seção.
const ALTURA_GRAFICO = 288; // h-72

// Máximo de séries desenhadas no gráfico de série temporal. Com 40 produtores
// e coleta diária, o gráfico virava uma parede de pontos E a legenda
// invadia o título do card. Acima do limite, o resto entra agregado em
// "Outros".
const MAX_SERIES = 12;
// Máximo de fatias da pizza (o resto vira "Outros"). Uma pizza de 40 fatias
// tem rótulos sobrepostos e não é legível.
const MAX_FATIAS = 10;

// Granularidade do gráfico "Produção ao Longo do Tempo". Sem isso, o gráfico
// desenhava UM PONTO POR DIA coletado por cada produtor: o gráfico mostrava
// quantidade de dados, não a tendência.
const GRANULARIDADES = [
  { value: 'dia', label: 'Dia' },
  { value: 'semana', label: 'Semana' },
  { value: 'mes', label: 'Mês' },
];

// Chave do balde de tempo de uma data ISO, conforme a granularidade.
const bucketDeData = (iso, granularidade) => {
  if (granularidade === 'semana') return chaveSemanaDeDataISO(iso);
  if (granularidade === 'mes') return chaveMesDeDataISO(iso);
  return iso;
};

// Rótulo do balde de tempo (eixo X).
const rotuloDoBucket = (chave, granularidade) => {
  if (granularidade === 'mes') return rotuloMes(chave);
  if (granularidade === 'semana') {
    // 'dd/mm' da segunda-feira que abre a semana.
    const br = dataBRDeDataISO(chave);
    return br ? br.slice(0, 5) : chave;
  }
  return dataBRDeDataISO(chave);
};

// Rótulo de mês/ano a partir de uma chave 'YYYY-MM' (sem `Intl`, sem fuso).
const rotuloMes = (chaveMes) => {
  const partes = String(chaveMes || '').split('-');
  if (partes.length !== 2) return String(chaveMes || '');
  const ano = Number(partes[0]);
  const mes = Number(partes[1]);
  if (!Number.isInteger(ano) || !Number.isInteger(mes) || mes < 1 || mes > 12) return String(chaveMes);
  const abrev = ['jan.', 'fev.', 'mar.', 'abr.', 'mai.', 'jun.', 'jul.', 'ago.', 'set.', 'out.', 'nov.', 'dez.'][mes - 1];
  return `${abrev.charAt(0).toUpperCase()}${abrev.slice(1)}/${String(ano).slice(-2)}`;
};

// Rótulo de mês/ano de um fechamento ('7/2026' -> 'Jul./26').
const rotuloMesAno = (mes, ano) => {
  const mesNum = Number(mes);
  const anoNum = Number(ano);
  if (!Number.isInteger(mesNum) || !Number.isInteger(anoNum)) return `${mes}/${ano}`;
  return rotuloMes(`${anoNum}-${String(mesNum).padStart(2, '0')}`);
};

// Descrição da força da correlação, a partir do coeficiente de Pearson.
const rotuloCorrelacao = (r) => {
  const abs = Math.abs(r);
  if (abs >= 0.8) return 'forte';
  if (abs >= 0.5) return 'moderada';
  if (abs >= 0.3) return 'fraca';
  return 'muito fraca';
};

// Ponto do gráfico de dispersão. Destaca (e rotula) os pontos extremos:
// sem isso, uma nuvem de 40 pontos iguais não diz nada.
const ScatterPoint = ({ cx, cy, payload, fill }) => {
  if (cx == null || cy == null || !payload) return null;
  const destaque = payload.destaque;
  return (
    <g>
      <circle
        cx={cx}
        cy={cy}
        r={destaque ? 6 : 4}
        fill={fill}
        fillOpacity={destaque ? 1 : 0.7}
        stroke="#ffffff"
        strokeWidth={1}
      />
      {destaque ? (
        <text x={cx + 8} y={cy - 6} fontSize={11} fill="#374151">{payload.name}</text>
      ) : null}
    </g>
  );
};

const ReportsPage = ({ apiService, token, setNotification }) => {
  const [producers, setProducers] = useState([]);
  const [collections, setCollections] = useState([]);
  const [closings, setClosings] = useState([]);
  const [isProducersLoading, setIsProducersLoading] = useState(true);
  const [isDataLoading, setIsDataLoading] = useState(true);

  // Filters
  const today = new Date();
  const firstDayOfMonth = new Date(today.getFullYear(), today.getMonth(), 1).toISOString().split('T')[0];
  const [startDate, setStartDate] = useState(firstDayOfMonth);
  const [endDate, setEndDate] = useState(today.toISOString().split('T')[0]);
  const [selectedProducers, setSelectedProducers] = useState([]);
  const [filterLine, setFilterLine] = useState('');
  const [tablePage, setTablePage] = useState(0);
  const [granularidade, setGranularidade] = useState('semana');
  const TABLE_PAGE_SIZE = 50;

  // Produtores carregam de forma independente do restante dos dados, para que
  // a barra de filtros fique utilizável assim que a lista chega, sem esperar
  // pelas coletas/fechamentos (potencialmente maiores).
  useEffect(() => {
    const fetchProducers = async () => {
      setIsProducersLoading(true);
      try {
        // `getAllProducers` percorre todas as páginas: o backend limita o
        // tamanho de página em 200 e devolveria uma lista truncada se
        // pedíssemos `size: 1000` (os graphs e filtros ficariam incompletos).
        const producersData = await apiService.getAllProducers(token);
        setProducers(producersData || []);
      } catch (error) {
        setNotification({ message: `Erro ao carregar produtores para os relatórios: ${error.message}`, type: 'error' });
      } finally {
        setIsProducersLoading(false);
      }
    };
    fetchProducers();
  }, [token, apiService, setNotification]);

  useEffect(() => {
    let cancelled = false;
    const timeoutId = setTimeout(() => {
      const fetchData = async () => {
        setIsDataLoading(true);
        try {
          // Filtro no servidor: `inicio`/`fim` limitam o período e `produtorId`
          // recorta por produtor, em vez de baixar o histórico inteiro e
          // filtrar no client (pesado a cada tecla e sem escalar). O backend
          // aceita apenas UM produtorId (sem `IN (...)`), então com vários
          // produtores selecionados disparamos uma busca por produtor e
          // combinamos — o filtro continua sendo feito no servidor. Sem
          // seleção, buscamos todos numa única chamada.
          const producerParamList = selectedProducers.length > 0 ? selectedProducers : [undefined];

          // Sem `.catch(() => [])` local: ele resolvia ANTES do try/catch de
          // baixo, que ficava código morto, e a tela mostrava gráficos
          // vazios e cards zerados sem nenhum aviso — indistinguível de "não
          // há dados no período". Deixa a falha subir para o catch reportar.
          const perProducer = await Promise.all(producerParamList.map((produtorId) => Promise.all([
            apiService.getAllColetasComAviso(
              token,
              { produtorId, inicio: startDate, fim: endDate },
              (total) => {
                if (!cancelled) {
                  setNotification({
                    message: `A lista de coletas foi truncada em ${total.toLocaleString('pt-BR')} registros: os totais do período podem estar subestimados.`,
                    type: 'warning',
                  });
                }
              },
            ),
            apiService.getAllFechamentosComAviso(
              token,
              { produtorId, inicio: startDate, fim: endDate },
              (total) => {
                if (!cancelled) {
                  setNotification({
                    message: `A lista de fechamentos foi truncada em ${total.toLocaleString('pt-BR')} registros: os totais do período podem estar subestimados.`,
                    type: 'warning',
                  });
                }
              },
            ),
          ])));

          if (cancelled) return;

          const collectionsData = perProducer.flatMap(([coletas]) => coletas || []);
          const closingsData = perProducer.flatMap(([, fechamentos]) => fechamentos || []);

          // --- DEDUPLICAÇÃO DE DADOS ---
          const collectionsMap = new Map();
          collectionsData.forEach(c => {
            const key = `${idTexto(c.produtorId)}-${c.data}`;
            if (!collectionsMap.has(key) || String(c.id) > String(collectionsMap.get(key).id)) {
              collectionsMap.set(key, c);
            }
          });
          setCollections(Array.from(collectionsMap.values()));

          // Fechamentos não têm chave natural de coleta; deduplica por id para
          // que a combinação multi-produtor não conte o mesmo registro duas vezes.
          const closingsMap = new Map();
          closingsData.forEach((f, idx) => {
            const key = f.id != null ? `id:${idTexto(f.id)}` : `sem-id:${idx}`;
            if (!closingsMap.has(key)) closingsMap.set(key, f);
          });
          setClosings(Array.from(closingsMap.values()));
          // -----------------------------
        } catch (error) {
          if (!cancelled) {
            setNotification({ message: `Erro ao carregar dados para os relatórios: ${error.message}`, type: 'error' });
          }
        } finally {
          if (!cancelled) setIsDataLoading(false);
        }
      };
      fetchData();
    }, 350);

    return () => {
      cancelled = true;
      clearTimeout(timeoutId);
    };
  }, [token, apiService, setNotification, startDate, endDate, selectedProducers]);

  // Ids são tratados como TEXTO (ver src/utils/ids.js): os ids do backend são
  // Snowflake (~1,22e18) e não cabem em um número do JavaScript. O valor
  // arredondado voltava para a API como filtro `produtorId` e o servidor não
  // encontrava nada — a tela zerava (0 L, gráficos vazios) ao selecionar um
  // produtor.
  const producerOptions = useMemo(() => producers.map(p => ({ value: idTexto(p.id), label: p.nome })), [producers]);

  const availableLines = useMemo(() => [...new Set(producers.map(p => p.linha).filter(Boolean))].sort(), [producers]);
  const producerLineMap = useMemo(() => new Map(producers.map(p => [idTexto(p.id), p.linha])), [producers]);
  const producerNameMap = useMemo(() => new Map(producers.map(p => [idTexto(p.id), p.nome])), [producers]);

  // Predicados compartilhados: evita reimplementar a mesma lógica de
  // data/produtor/linha em filteredData, summaryCards e qualityMetricsData
  // (o que já causou drift entre elas antes desta extração).
  //
  // useCallback porque são lidos dentro de useMemo: sem identidade estável
  // entre renders, cada um seria um predicado novo a cada render e os
  // memoizados recalculariam sempre (e a lista de dependências ficaria
  // incorreta).
  const coletaMatchesFilters = useCallback((c, start, end) => {
    const collectionDate = new Date(c.data);
    const producerId = idTexto(c.produtorId || c.produtor?.id);
    const producerLine = producerLineMap.get(producerId);
    const matchesLine = filterLine ? producerLine === filterLine : true;

    return collectionDate >= start && collectionDate <= end &&
      producerId && (selectedProducers.length === 0 || selectedProducers.includes(producerId)) &&
      matchesLine;
  }, [producerLineMap, filterLine, selectedProducers]);

  const fechamentoMatchesFilters = useCallback((f) => {
    // Ancorar pela `dataFim` do DTO, e não por `new Date(f.ano, f.mes - 1, 1)`:
    // períodos podem atravessar meses (padrão de pagamento do dia 20 ao dia 20,
    // ex.: 20/06–20/07), e ancorar no dia 1º do mês deslocava o registro em ~20
    // dias — um recorte que começa no meio do mês deixava o fechamento de fora
    // mesmo com a `dataFim` dentro do período.
    //
    // `dataFim`, `startDate` e `endDate` são strings ISO (YYYY-MM-DD), a MESMA
    // chave UTC usada na tabela/timeline: comparar as strings evita reinterpretar
    // a data no fuso local (o que reintroduziria o bug de fuso do achado 23).
    const withinRange = f.dataFim != null && f.dataFim >= startDate && f.dataFim <= endDate;
    const producerId = idTexto(f.produtorId);
    const producerLine = producerLineMap.get(producerId);
    const matchesLine = filterLine ? producerLine === filterLine : true;

    return withinRange && producerId &&
      (selectedProducers.length === 0 || selectedProducers.includes(producerId)) &&
      matchesLine;
  }, [producerLineMap, filterLine, selectedProducers, startDate, endDate]);

  const relevantClosings = useMemo(() => {
    return closings.filter(f => fechamentoMatchesFilters(f));
  }, [closings, fechamentoMatchesFilters]);

  const filteredData = useMemo(() => {
    const start = new Date(startDate);
    const end = new Date(endDate);
    end.setHours(23, 59, 59, 999);

    const filteredCollections = collections.filter(c => coletaMatchesFilters(c, start, end));

    return filteredCollections.map(c => ({
      ...c,
      // Nome e linha do produtor resolvidos pelo id (texto, não número).
      produtorName: producerNameMap.get(idTexto(c.produtorId || c.produtor?.id)) || 'Desconhecido',
      produtorLinha: producerLineMap.get(idTexto(c.produtorId || c.produtor?.id)) || 'Sem Linha',
    }));
  }, [startDate, endDate, collections, producerNameMap, producerLineMap, coletaMatchesFilters]);

  // Determine View Mode: 'line' (aggregated) or 'producer' (specific)
  // Logic: Show by Line if NO specific producers are selected AND (NO line filter OR line filter is active but we want to see producers... wait).
  // Request: "change default ... to show production by linha ... having option to filter to see each linha produtores"
  // Interpretation:
  // 1. Default (No filters): Show Lines.
  // 2. Line Filter Selected: Show Producers (of that line).
  // 3. Producer Filter Selected: Show Producers (selected).
  const viewMode = (selectedProducers.length === 0 && !filterLine) ? 'line' : 'producer';

  const summaryCards = useMemo(() => {
    // Todos os cards precisam de TODAS as chaves: o early-return abaixo
    // devolvia só 4, e os outros 6 apareciam VAZIOS na tela (sem "R$ 0,00",
    // sem "0 L") quando o período não tinha coletas.
    const vazio = {
      totalLitros: '0',
      mediaDiaria: '0,00',
      maiorProdutor: 'N/A',
      medianaPorProdutor: '0',
      totalLitrosPagos: '0',
      valorTotalLeite: 'R$ 0,00',
      totalAdiantamentos: 'R$ 0,00',
      valorPagoNoFechamento: 'R$ 0,00',
      precoMedio: 'R$ 0,00',
      emHaver: 'R$ 0,00',
    };
    if (filteredData.length === 0) return vazio;

    const totalLitros = filteredData.reduce((acc, c) => acc + c.quantidadeLitros, 0);

    // Calcula a diferença de dias corretamente, evitando divisão por zero
    const daysInRange = Math.max(1, (new Date(endDate) - new Date(startDate)) / (1000 * 60 * 60 * 24) + 1);
    const mediaDiaria = totalLitros / daysInRange;

    const producerTotals = filteredData.reduce((acc, c) => {
      const prodId = idTexto(c.produtorId || c.produtor?.id);
      if (prodId) {
        acc[prodId] = (acc[prodId] || 0) + c.quantidadeLitros;
      }
      return acc;
    }, {});

    let maiorProdutor = 'N/A';
    let maxLitros = 0;
    for (const prodId in producerTotals) {
      if (producerTotals[prodId] > maxLitros) {
        maxLitros = producerTotals[prodId];
        maiorProdutor = producerNameMap.get(prodId) || 'Desconhecido';
      }
    }

    // Calculate Median Liters
    const totalsArray = Object.values(producerTotals).sort((a, b) => a - b);
    let medianLiters = 0;
    if (totalsArray.length > 0) {
      const mid = Math.floor(totalsArray.length / 2);
      medianLiters = totalsArray.length % 2 !== 0
        ? totalsArray[mid]
        : (totalsArray[mid - 1] + totalsArray[mid]) / 2;
    }

    const totalAdiantamentos = relevantClosings.reduce((acc, f) => acc + Math.abs(f.descontos || 0), 0);
    const totalLitrosPagos = relevantClosings.reduce((acc, f) => acc + (f.totalLitros || 0), 0);
    // valorPagoNoFechamento = net payment at fechamento time (can be negative if adiantamentos > milk value)
    const valorPagoNoFechamento = relevantClosings.reduce((acc, f) => acc + f.totalLiquido, 0);
    // gross milk value = what the milk was worth = net payment + advances already paid
    const valorTotalLeite = valorPagoNoFechamento + totalAdiantamentos;

    return {
      totalLitros: totalLitros.toLocaleString('pt-BR'),
      mediaDiaria: mediaDiaria.toFixed(2).replace('.', ','),
      maiorProdutor,
      totalLitrosPagos: totalLitrosPagos.toLocaleString('pt-BR'),
      valorTotalLeite: valorTotalLeite.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' }),
      totalAdiantamentos: totalAdiantamentos.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' }),
      valorPagoNoFechamento: valorPagoNoFechamento.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' }),
      precoMedio: (totalLitrosPagos > 0 && valorTotalLeite > 0)
        ? (valorTotalLeite / totalLitrosPagos).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })
        : 'R$ 0,00',
      medianaPorProdutor: medianLiters.toLocaleString('pt-BR', { maximumFractionDigits: 2 }),
      emHaver: relevantClosings
        .filter(f => f.totalLiquido < 0)
        .reduce((acc, f) => acc + f.totalLiquido, 0)
        .toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' }),
    };
  }, [filteredData, producerNameMap, relevantClosings, endDate, startDate]);

  // --- Data Preparation for Production Graphs ---
  const productionGraphsData = useMemo(() => {
    // 1. Total Monthly Volume Trend
    // Usa os fechamentos FILTRADOS (antes usava `closings` cru): com o filtro
    // de produtor/linha aplicado, o gráfico continuava mostrando o volume de
    // TODOS os produtores — o gráfico contradizia o que a tela mostrava acima.
    const monthlyVolumeMap = {};
    relevantClosings.forEach(c => {
      const key = `${c.mes}/${c.ano}`;
      if (!monthlyVolumeMap[key]) monthlyVolumeMap[key] = { name: rotuloMesAno(c.mes, c.ano), volume: 0, sortDate: new Date(c.ano, c.mes - 1) };
      monthlyVolumeMap[key].volume += c.totalLitros || 0;
    });
    const monthlyVolumeData = Object.values(monthlyVolumeMap).sort((a, b) => a.sortDate - b.sortDate);

    // 2. Volatilidade da coleta: média e dispersão por semana.
    //
    // Antes era uma barra por DIA do período, ocupando a linha inteira da
    // grade (com o título prometendo "último período" e mostrando o período
    // todo). Uma barra por dia não responde pergunta nenhuma; o que interessa
    // é quanto a coleta varia — média e desvio, por semana.
    //
    // `c.data` (string ISO YYYY-MM-DD) é a chave de balde — a MESMA da tabela
    // e da timeline. A chave de semana (segunda-feira) sai de
    // `chaveSemanaDeDataISO`, sem `Date` por linha.
    const porDia = new Map();
    filteredData.forEach(c => {
      const iso = chaveDataISO(c.data);
      if (!iso) return;
      porDia.set(iso, (porDia.get(iso) || 0) + (c.quantidadeLitros || 0));
    });

    const porSemana = new Map();
    for (const [iso, volume] of porDia) {
      const chave = chaveSemanaDeDataISO(iso);
      if (!chave) continue;
      if (!porSemana.has(chave)) porSemana.set(chave, []);
      porSemana.get(chave).push(volume);
    }

    const volatilityData = [...porSemana.entries()]
      .sort((a, b) => (a[0] < b[0] ? -1 : 1))
      .map(([chave, valores]) => {
        const media = valores.reduce((acc, v) => acc + v, 0) / valores.length;
        const variancia = valores.reduce((acc, v) => acc + (v - media) ** 2, 0) / valores.length;
        const desvio = Math.sqrt(variancia);
        return {
          name: dataBRDeDataISO(chave).slice(0, 5),
          media,
          desvio,
          base: Math.max(0, media - desvio),
          amplitude: Math.max(0, media + desvio) - Math.max(0, media - desvio),
          dias: valores.length,
        };
      });

    const mediaGeral = volatilityData.length > 0
      ? volatilityData.reduce((acc, d) => acc + d.media, 0) / volatilityData.length
      : 0;

    // 3. Top 10 Producers by Volume
    const producerTotals = {};
    filteredData.forEach(c => {
      const name = c.produtorName || 'Desconhecido';
      producerTotals[name] = (producerTotals[name] || 0) + c.quantidadeLitros;
    });
    const top10ProducersData = Object.entries(producerTotals)
      .map(([name, volume]) => ({ name, volume }))
      .sort((a, b) => b.volume - a.volume)
      .slice(0, 10);

    return { monthlyVolumeData, volatilityData, mediaGeral, top10ProducersData };
  }, [relevantClosings, filteredData]);


  // --- Quality Metrics Data ---
  const qualityMetricsData = useMemo(() => {
    // Group closings by month for the graph
    const dataByMonth = {};

    relevantClosings.forEach(f => {
      const closingDate = new Date(f.ano, f.mes - 1, 1);
      const key = `${f.mes}/${f.ano}`; // Group by Month/Year
      if (!dataByMonth[key]) {
        dataByMonth[key] = {
          name: rotuloMesAno(f.mes, f.ano),
          dateObj: closingDate,
          count: 0,
          sumCBT: 0,
          sumCCS: 0,
          sumGordura: 0,
          sumProteina: 0
        };
      }

      dataByMonth[key].count++;
      dataByMonth[key].sumCBT += (f.cbt || 0);
      dataByMonth[key].sumCCS += (f.ccs || 0);
      dataByMonth[key].sumGordura += (f.gordura || 0);
      dataByMonth[key].sumProteina += (f.proteina || 0);
    });

    return Object.values(dataByMonth)
      .sort((a, b) => a.dateObj - b.dateObj)
      .map(item => ({
        name: item.name,
        cbt: item.count ? Math.round(item.sumCBT / item.count) : 0,
        ccs: item.count ? Math.round(item.sumCCS / item.count) : 0,
        gordura: item.count ? parseFloat((item.sumGordura / item.count).toFixed(2)) : 0,
        proteina: item.count ? parseFloat((item.sumProteina / item.count).toFixed(2)) : 0,
      }));
  }, [relevantClosings]);

  // --- Financial & Scatter Data ---
  const financialGraphsData = useMemo(() => {
    // 1. Avg Price History (Stacked Area: Base vs Bonus)
    // Fechamentos FILTRADOS: antes usava `closings` cru e ignorava o filtro
    // de produtor/linha da barra superior.
    const priceMap = {};
    relevantClosings.forEach(c => {
      const key = `${c.mes}/${c.ano}`;
      if (!priceMap[key]) priceMap[key] = { name: rotuloMesAno(c.mes, c.ano), count: 0, sumBase: 0, sumFinal: 0, sortDate: new Date(c.ano, c.mes - 1) };
      priceMap[key].count++;
      // Use precoLitro as Base Price (fallback to precoBase or 0)
      const basePrice = c.precoLitro || c.precoBase || 0;
      priceMap[key].sumBase += basePrice;
      priceMap[key].sumFinal += c.precoFinal || 0;
    });

    const priceHistoryData = Object.values(priceMap)
      .sort((a, b) => a.sortDate - b.sortDate)
      .map(item => ({
        name: item.name,
        PrecoBase: parseFloat((item.sumBase / item.count).toFixed(2)),
        Bonificacoes: parseFloat(((item.sumFinal - item.sumBase) / item.count).toFixed(2))
      }));

    // 2. Correlação: Volume vs Preço (Scatter)
    //
    // Fechamentos FILTRADOS (antes: `closings` cru, ignorando os filtros) e com
    // o nome do produtor RESOLVIDO pelo id: o DTO não traz `produtorNome`, e o
    // tooltip caía sempre em "Produtor".
    const scatterData = relevantClosings
      .filter(c => Number.isFinite(c.totalLitros) && Number.isFinite(c.precoFinal))
      .map(c => ({
        x: c.totalLitros,
        y: c.precoFinal,
        name: producerNameMap.get(idTexto(c.produtorId)) || c.produtorNome || 'Produtor',
        linha: producerLineMap.get(idTexto(c.produtorId)) || 'Sem Linha',
      }));

    return { priceHistoryData, scatterData };
  }, [relevantClosings, producerNameMap, producerLineMap]);

  // Regressão linear + coeficiente de Pearson sobre a dispersão. É o que
  // faz o gráfico "Correlação" dizer o que o título promete.
  const regressao = useMemo(() => {
    const pontos = financialGraphsData.scatterData;
    const n = pontos.length;
    if (n < 2) return null;

    const mediaX = pontos.reduce((acc, p) => acc + p.x, 0) / n;
    const mediaY = pontos.reduce((acc, p) => acc + p.y, 0) / n;

    let sxy = 0; let sxx = 0; let syy = 0;
    pontos.forEach(p => {
      sxy += (p.x - mediaX) * (p.y - mediaY);
      sxx += (p.x - mediaX) ** 2;
      syy += (p.y - mediaY) ** 2;
    });
    if (sxx === 0 || syy === 0) return null;

    const r = sxy / Math.sqrt(sxx * syy);
    const inclinacao = sxy / sxx;
    const intercepto = mediaY - inclinacao * mediaX;

    const xs = pontos.map(p => p.x);
    const x1 = Math.min(...xs);
    const x2 = Math.max(...xs);

    return {
      r,
      inclinacao,
      intercepto,
      segmento: [
        { x: x1, y: inclinacao * x1 + intercepto },
        { x: x2, y: inclinacao * x2 + intercepto },
      ],
    };
  }, [financialGraphsData.scatterData]);

  // Uma série por linha do produtor (identifica quem é quem) e destaque dos
  // pontos extremos (maior volume e maior preço), que são os que interessam.
  const scatterSeries = useMemo(() => {
    const pontos = financialGraphsData.scatterData;
    if (pontos.length === 0) return [];

    const porVolume = [...pontos].sort((a, b) => b.x - a.x).slice(0, 2);
    const porPreco = [...pontos].sort((a, b) => b.y - a.y).slice(0, 2);
    const destacados = new Set([...porVolume, ...porPreco]);
    const marcados = pontos.map(p => ({ ...p, destaque: destacados.has(p) }));

    const porLinha = new Map();
    marcados.forEach(p => {
      if (!porLinha.has(p.linha)) porLinha.set(p.linha, []);
      porLinha.get(p.linha).push(p);
    });

    return [...porLinha.entries()].map(([linha, itens]) => ({ linha, itens }));
  }, [financialGraphsData.scatterData]);

  // Produtores que ganham série própria no gráfico de série temporal. Com
  // 40 produtores, o gráfico e a legenda viravam um bloco de texto: o limite
  // mantém o gráfico legível e o resto vai para "Outros".
  const topSeriesProducers = useMemo(() => {
    if (viewMode !== 'producer') return [];
    const totais = new Map();
    filteredData.forEach(c => {
      const nome = c.produtorName || 'Desconhecido';
      totais.set(nome, (totais.get(nome) || 0) + (c.quantidadeLitros || 0));
    });
    return [...totais.entries()]
      .sort((a, b) => b[1] - a[1])
      .slice(0, MAX_SERIES)
      .map(([nome]) => nome);
  }, [filteredData, viewMode]);

  const productionOverTimeData = useMemo(() => {
    // Chave ISO como chave do balde e data BR como rótulo (issue #34).
    //
    // Antes: `new Date(c.data).toLocaleDateString('pt-BR', { timeZone: 'UTC' })`
    // por linha — ~1,4 s de JS bloqueante sobre ~7.000 coletas, o que travava
    // a main thread (e portanto toda animação) durante a montagem do gráfico.
    // A string ISO 'YYYY-MM-DD' JÁ é a chave UTC canônica: para exibir, basta
    // fatiar, e para ordenar, a comparação lexicográfica das strings ISO já é
    // cronológica — sem reconstruir `Date` a cada comparação do `sort`.
    //
    // Agora o balde é o período escolhido (dia/semana/mês): sem isso, o
    // gráfico tinha UM PONTO POR DIA por produtor — uma parede de círculos
    // que não mostrava tendência. A chave do balde também ordena
    // lexicograficamente (ISO 'YYYY-MM-DD' ou 'YYYY-MM').
    const dataByBucket = new Map();
    // Cache data -> balde: a chave da semana é cara (Date) e há poucas datas
    // distintas para milhares de coletas.
    const cacheBucket = new Map();

    filteredData.forEach(c => {
      const iso = chaveDataISO(c.data);
      if (!iso) return; // data fora do formato ISO não entra no gráfico

      let chave = cacheBucket.get(iso);
      if (chave === undefined) {
        chave = bucketDeData(iso, granularidade);
        cacheBucket.set(iso, chave);
      }
      if (!chave) return;

      let ponto = dataByBucket.get(chave);
      if (!ponto) {
        ponto = { name: rotuloDoBucket(chave, granularidade) };
        dataByBucket.set(chave, ponto);
      }

      let key = viewMode === 'line' ? c.produtorLinha : c.produtorName;
      // Produtor fora do limite de séries entra agregado em "Outros".
      if (viewMode === 'producer' && !topSeriesProducers.includes(key)) key = 'Outros';

      if (ponto[key] === undefined) {
        ponto[key] = 0;
      }
      ponto[key] += (c.quantidadeLitros || 0);
    });

    return [...dataByBucket.entries()]
      .sort((a, b) => (a[0] < b[0] ? -1 : 1)) // ISO ordena lexicograficamente = cronologicamente
      .map(([, ponto]) => ponto);
  }, [filteredData, viewMode, granularidade, topSeriesProducers]);

  const productionByEntityData = useMemo(() => {
    const totals = filteredData.reduce((acc, c) => {
      const key = viewMode === 'line' ? c.produtorLinha : c.produtorName;
      acc[key] = (acc[key] || 0) + c.quantidadeLitros;
      return acc;
    }, {});
    const entradas = Object.entries(totals)
      .map(([name, value]) => ({ name, value }))
      .sort((a, b) => b.value - a.value);

    // Pizza de 40 fatias tem rótulos sobrepostos: o excedente vira "Outros".
    if (entradas.length <= MAX_FATIAS) return entradas;
    const principais = entradas.slice(0, MAX_FATIAS);
    const outros = entradas.slice(MAX_FATIAS).reduce((acc, e) => acc + e.value, 0);
    return [...principais, { name: `Outros (${entradas.length - MAX_FATIAS})`, value: outros, outros: true }];
  }, [filteredData, viewMode]);

  // As séries do gráfico de série temporal saem de `topSeriesProducers` (os
  // produtores com maior volume no período, até o limite) — a lista de
  // produtores filtrados não é mais usada para desenhar série.

  // Determine entities for consistent coloring
  const entities = useMemo(() => {
    if (viewMode === 'line') {
      return availableLines;
    }
    return topSeriesProducers;
  }, [viewMode, availableLines, topSeriesProducers]);

  const entityColorMap = useMemo(() => {
    const map = {};
    entities.forEach((entity, index) => {
      map[entity] = COLORS[index % COLORS.length];
    });
    return map;
  }, [entities]);

  // Evita que uma mudança de filtro deixe o usuário numa página fora do
  // intervalo válido da tabela.
  useEffect(() => {
    setTablePage(0);
  }, [filteredData]);

  const tableTotalRows = filteredData.length;
  const tablePageStart = tablePage * TABLE_PAGE_SIZE;
  const tablePageRows = filteredData.slice(tablePageStart, tablePageStart + TABLE_PAGE_SIZE);
  const tableTotalPages = Math.max(1, Math.ceil(tableTotalRows / TABLE_PAGE_SIZE));

  // Séries desenhadas no gráfico de série temporal (inclui "Outros" quando
  // há produtor fora do limite).
  const seriesTempo = useMemo(() => {
    if (viewMode === 'line') return availableLines;
    const lista = [...topSeriesProducers];
    if (productionOverTimeData.some(p => p.Outros !== undefined)) lista.push('Outros');
    return lista;
  }, [viewMode, availableLines, topSeriesProducers, productionOverTimeData]);

  if (isProducersLoading) {
    return <div className="text-center p-8">Carregando dados...</div>;
  }

  return (
    <div className="anim-pagina">
      <h1 className="text-3xl font-bold text-gray-800 mb-6">Relatórios e Gráficos</h1>

      {/* --- Filtros --- */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4 mb-8 p-4 bg-white rounded-xl shadow-sm">
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">Linha/Região</label>
          <select value={filterLine} onChange={(e) => setFilterLine(e.target.value)} className="input-field">
            <option value="">Todas</option>
            {availableLines.map(line => <option key={line} value={line}>{line}</option>)}
          </select>
        </div>
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">Data Início</label>
          <input type="date" value={startDate} onChange={e => setStartDate(e.target.value)} className="input-field" />
        </div>
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">Data Fim</label>
          <input type="date" value={endDate} onChange={e => setEndDate(e.target.value)} className="input-field" />
        </div>
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">Produtores</label>
          <MultiSelect
            options={producerOptions}
            value={selectedProducers}
            onChange={setSelectedProducers}
            placeholder="Selecione os produtores..."
            className="w-full"
          />
        </div>
      </div>

      {isDataLoading ? (
        <div className="text-center p-8">Carregando dados do período selecionado...</div>
      ) : (
      <>
      {/* --- Cards de Resumo ---
          10 cards em 5 colunas: 5 + 5, sem célula vazia no fim (com 4
          colunas sobravam dois buracos ao lado do "Em haver"). */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-5 gap-6 mb-8">
        <div className="card"><p className="text-gray-500 text-sm">Total de Litros no Período</p><p className="text-2xl font-bold">{summaryCards.totalLitros} L</p></div>
        <div className="card"><p className="text-gray-500 text-sm">Valor Total do Leite</p><p className="text-2xl font-bold">{summaryCards.valorTotalLeite}</p></div>
        <div className="card"><p className="text-gray-500 text-sm">Preço Médio do Litro</p><p className="text-2xl font-bold text-green-600">{summaryCards.precoMedio}</p></div>
        <div className="card"><p className="text-gray-500 text-sm">Valor Pago no Fechamento</p><p className="text-2xl font-bold text-blue-600">{summaryCards.valorPagoNoFechamento}</p></div>
        <div className="card !border-red-200"><p className="text-gray-500 text-sm">Em haver</p><p className="text-2xl font-bold text-red-600">{summaryCards.emHaver}</p></div>

        <div className="card"><p className="text-gray-500 text-sm">Média Diária</p><p className="text-2xl font-bold">{summaryCards.mediaDiaria} L</p></div>
        <div className="card"><p className="text-gray-500 text-sm">Mediana por Produtor</p><p className="text-2xl font-bold">{summaryCards.medianaPorProdutor} L</p></div>
        <div className="card"><p className="text-gray-500 text-sm">Produtor com Maior Coleta</p><p className="text-2xl font-bold">{summaryCards.maiorProdutor}</p></div>
        <div className="card"><p className="text-gray-500 text-sm">Quant. Litros Pagos</p><p className="text-2xl font-bold">{summaryCards.totalLitrosPagos} L</p></div>
        <div className="card"><p className="text-gray-500 text-sm">Adiantamentos Pagos</p><p className="text-2xl font-bold text-orange-500">{summaryCards.totalAdiantamentos}</p></div>
      </div>

      {/* --- Visão geral ---
          4 células em 2 colunas (2 linhas cheias): a série temporal ocupa a
          linha inteira e a pizza + tendência mensal dividem a de baixo. */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-8 mb-8">
        <div className="card lg:col-span-2">
          <div className="flex items-start justify-between gap-4 mb-4">
            <div>
              <h2 className="text-xl font-semibold">Produção ao Longo do Tempo</h2>
              <p className="text-sm text-gray-500">
                {viewMode === 'line' ? 'Volume por linha' : `Volume por produtor (top ${MAX_SERIES}, resto em "Outros")`}
              </p>
            </div>
            <div className="flex items-center gap-2">
              <label htmlFor="granularidade-relatorios" className="text-sm text-gray-600">Granularidade</label>
              <select
                id="granularidade-relatorios"
                value={granularidade}
                onChange={e => setGranularidade(e.target.value)}
                className="input-field w-auto"
              >
                {GRANULARIDADES.map(g => <option key={g.value} value={g.value}>{g.label}</option>)}
              </select>
            </div>
          </div>
          <div style={{ height: ALTURA_GRAFICO }}>
            <ChartAoRolar altura="100%">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={productionOverTimeData} margin={{ top: 5, right: 30, left: 20, bottom: 5 }}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="name" interval="preserveStartEnd" minTickGap={24} />
                  <YAxis width={60} tickFormatter={(v) => v.toLocaleString('pt-BR')} />
                  <Tooltip formatter={(value, name) => [`${Number(value).toLocaleString('pt-BR')} L`, name]} />
                  <Legend wrapperStyle={{ fontSize: 12 }} />

                  {seriesTempo.map((serie) => (
                    <Line
                      animationDuration={300} animationEasing="ease-out"
                      key={serie}
                      type="monotone"
                      dataKey={serie}
                      stroke={serie === 'Outros' ? COR_OUTROS : entityColorMap[serie]}
                      strokeWidth={2}
                      // Sem `dot`, o recharts desenha um círculo em CADA ponto:
                      // com coleta diária e 40 produtores, o gráfico virava uma
                      // parede de pontos. A tendência é a informação.
                      dot={false}
                      activeDot={{ r: 4 }}
                      // Sem `connectNulls`, a linha quebrava nos dias sem coleta
                      // e o gráfico virava pontos soltos.
                      connectNulls
                    />
                  ))}
                </LineChart>
              </ResponsiveContainer>
            </ChartAoRolar>
          </div>
        </div>
        <div className="card">
          <h2 className="text-xl font-semibold mb-4">
            {viewMode === 'line' ? 'Participação por Linha' : 'Participação por Produtor'}
          </h2>
          <div style={{ height: ALTURA_GRAFICO }}>
            <ChartAoRolar altura="100%">
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie data={productionByEntityData} dataKey="value" nameKey="name" cx="50%" cy="50%" outerRadius={100} fill="#8884d8" animationDuration={300} animationEasing="ease-out">
                    {productionByEntityData.map((entry, index) => (
                      <Cell key={`cell-${index}`} fill={entry.outros ? COR_OUTROS : (entityColorMap[entry.name] || '#8884d8')} />
                    ))}
                  </Pie>
                  <Tooltip formatter={(value, name) => [`${Number(value).toLocaleString('pt-BR')} L`, name]} />
                  <Legend wrapperStyle={{ fontSize: 12 }} />
                </PieChart>
              </ResponsiveContainer>
            </ChartAoRolar>
          </div>
        </div>
        <div className="card">
          <h3 className="text-xl font-semibold mb-4">Tendência Mensal de Volume</h3>
          <div style={{ height: ALTURA_GRAFICO }}>
            <ChartAoRolar altura="100%">
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={productionGraphsData.monthlyVolumeData}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="name" />
                  <YAxis tickFormatter={(v) => v.toLocaleString('pt-BR')} />
                  <Tooltip formatter={(value) => [`${Number(value).toLocaleString('pt-BR')} L`, 'Volume']} />
                  <Legend />
                  <Area type="monotone" dataKey="volume" name="Volume Total (L)" stroke="#2563eb" fill="#3b82f6" fillOpacity={0.3} animationDuration={300} animationEasing="ease-out" />
                </AreaChart>
              </ResponsiveContainer>
            </ChartAoRolar>
          </div>
        </div>
      </div>

      {/* --- Gráficos de Produção e Volume --- */}
      <h2 className="text-2xl font-bold mb-6 text-gray-800 border-l-4 border-blue-600 pl-4">Análise de Produção e Volume</h2>
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-8 mb-12">
        {/* 1. Top 10 Produtores */}
        <div className="card">
          <h3 className="text-xl font-semibold mb-4">Top 10 Produtores por Volume</h3>
          <div style={{ height: ALTURA_GRAFICO }}>
            <ChartAoRolar altura="100%">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart layout="vertical" data={productionGraphsData.top10ProducersData}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis type="number" tickFormatter={(v) => v.toLocaleString('pt-BR')} />
                  <YAxis dataKey="name" type="category" width={100} tick={{ fontSize: 12 }} />
                  <Tooltip formatter={(value) => [`${Number(value).toLocaleString('pt-BR')} L`, 'Volume']} />
                  <Bar dataKey="volume" name="Volume (L)" fill="#10b981" radius={[0, 4, 4, 0]} animationDuration={300} animationEasing="ease-out" />
                </BarChart>
              </ResponsiveContainer>
            </ChartAoRolar>
          </div>
        </div>

        {/* 2. Volatilidade: média e dispersão por semana.
            Antes ocupava a LINHA INTEIRA com uma barra por dia do período
            (e o título prometia "último período" sem cortar nada). Agora
            ocupa meia linha e responde o que o nome diz: quanto a coleta
            varia. */}
        <div className="card">
          <h3 className="text-xl font-semibold mb-4">Volatilidade da Coleta (Média e Dispersão por Semana)</h3>
          <div style={{ height: ALTURA_GRAFICO }}>
            <ChartAoRolar altura="100%">
              <ResponsiveContainer width="100%" height="100%">
                <ComposedChart data={productionGraphsData.volatilityData}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="name" />
                  <YAxis tickFormatter={(v) => v.toLocaleString('pt-BR')} />
                  <Tooltip
                    content={({ active, payload }) => {
                      if (!active || !payload || !payload.length) return null;
                      const d = payload[0].payload;
                      return (
                        <div className="bg-white border border-gray-200 rounded-lg shadow p-3 text-sm">
                          <p className="font-semibold">Semana de {d.name}</p>
                          <p>Média: {d.media.toLocaleString('pt-BR', { maximumFractionDigits: 0 })} L</p>
                          <p>Desvio: {d.desvio.toLocaleString('pt-BR', { maximumFractionDigits: 0 })} L</p>
                          <p className="text-gray-500">{d.dias} dia(s) com coleta</p>
                        </div>
                      );
                    }}
                  />
                  <Legend wrapperStyle={{ fontSize: 12 }} />
                  {/* Faixa de dispersão: área "base" (média - desvio) invisível +
                      área "amplitude" empilhada em cima. */}
                  <Area dataKey="base" stackId="faixa" stroke="none" fill="transparent" legendType="none" animationDuration={300} animationEasing="ease-out" />
                  <Area dataKey="amplitude" stackId="faixa" name="Dispersão (média ± desvio)" stroke="#f59e0b" fill="#f59e0b" fillOpacity={0.25} animationDuration={300} animationEasing="ease-out" />
                  <Line dataKey="media" name="Média semanal" stroke="#b45309" strokeWidth={2} dot={false} animationDuration={300} animationEasing="ease-out" />
                  <ReferenceLine
                    y={productionGraphsData.mediaGeral}
                    stroke="#9ca3af"
                    strokeDasharray="4 4"
                    label={{ value: 'Média do período', position: 'insideTopRight', fontSize: 11 }}
                  />
                </ComposedChart>
              </ResponsiveContainer>
            </ChartAoRolar>
          </div>
        </div>
      </div>

      {/* --- Gráficos de Qualidade --- */}
      <h2 className="text-2xl font-bold mb-6 text-gray-800 border-l-4 border-purple-600 pl-4">Qualidade do Leite</h2>
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-8 mb-12">
        <div className="card">
          <h2 className="text-xl font-semibold mb-4">Qualidade: Gordura e Proteína (%)</h2>
          <div style={{ height: ALTURA_GRAFICO }}>
            <ChartAoRolar altura="100%">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={qualityMetricsData} margin={{ top: 5, right: 30, left: 20, bottom: 5 }}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="name" />
                  <YAxis />
                  <Tooltip />
                  <Legend wrapperStyle={{ fontSize: 12 }} />
                  <Line type="monotone" dataKey="gordura" name="Gordura (%)" stroke="#FF8042" strokeWidth={2} dot={false} connectNulls animationDuration={300} animationEasing="ease-out" />
                  <Line type="monotone" dataKey="proteina" name="Proteína (%)" stroke="#00C49F" strokeWidth={2} dot={false} connectNulls animationDuration={300} animationEasing="ease-out" />
                </LineChart>
              </ResponsiveContainer>
            </ChartAoRolar>
          </div>
        </div>
        <div className="card">
          <h2 className="text-xl font-semibold mb-4">Qualidade: CBT e CCS (x1000)</h2>
          <div style={{ height: ALTURA_GRAFICO }}>
            <ChartAoRolar altura="100%">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={qualityMetricsData} margin={{ top: 5, right: 30, left: 20, bottom: 5 }}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="name" />
                  <YAxis />
                  <Tooltip />
                  <Legend wrapperStyle={{ fontSize: 12 }} />
                  <Line type="monotone" dataKey="cbt" name="CBT" stroke="#8884d8" strokeWidth={2} dot={false} connectNulls animationDuration={300} animationEasing="ease-out" />
                  <Line type="monotone" dataKey="ccs" name="CCS" stroke="#82ca9d" strokeWidth={2} dot={false} connectNulls animationDuration={300} animationEasing="ease-out" />
                </LineChart>
              </ResponsiveContainer>
            </ChartAoRolar>
          </div>
        </div>
      </div>

      {/* --- Gráficos Financeiros --- */}
      <h2 className="text-2xl font-bold mb-6 text-gray-800 border-l-4 border-green-600 pl-4">Análise Financeira</h2>
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-8 mb-12">
        {/* 1. Histórico de Preço Médio */}
        <div className="card">
          <h3 className="text-xl font-semibold mb-4">Histórico de Composição do Preço</h3>
          <div style={{ height: ALTURA_GRAFICO }}>
            <ChartAoRolar altura="100%">
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={financialGraphsData.priceHistoryData}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="name" />
                  <YAxis tickFormatter={(val) => `R$ ${val}`} />
                  <Tooltip formatter={(value) => [`R$ ${Number(value).toFixed(2)}`, '']} />
                  <Legend wrapperStyle={{ fontSize: 12 }} />
                  <Area type="monotone" dataKey="PrecoBase" name="Preço Base" stackId="1" stroke="#8884d8" fill="#8884d8" animationDuration={300} animationEasing="ease-out" />
                  <Area type="monotone" dataKey="Bonificacoes" name="Bonificações" stackId="1" stroke="#82ca9d" fill="#82ca9d" animationDuration={300} animationEasing="ease-out" />
                </AreaChart>
              </ResponsiveContainer>
            </ChartAoRolar>
          </div>
        </div>

        {/* 2. Correlação Volume x Preço
            Antes: uma nuvem de pontos sem nome, sem linha de tendência e sem
            respeitar os filtros — o título prometia "correlação" e o gráfico
            não mostrava nenhuma. */}
        <div className="card">
          <h3 className="text-xl font-semibold mb-1">Correlação: Volume vs Preço Final</h3>
          <p className="text-sm text-gray-500 mb-4">
            {regressao
              ? `r = ${regressao.r.toFixed(2)} (correlação ${regressao.r < 0 ? 'negativa' : 'positiva'} ${rotuloCorrelacao(regressao.r)}) · tendência: preço ${regressao.inclinacao.toFixed(4)} por litro`
              : 'Dados insuficientes para calcular a correlação'}
          </p>
          <div style={{ height: ALTURA_GRAFICO }}>
            <ChartAoRolar altura="100%">
              <ResponsiveContainer width="100%" height="100%">
                <ScatterChart margin={{ top: 20, right: 30, bottom: 20, left: 20 }}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis type="number" dataKey="x" name="Volume" unit=" L" tickFormatter={(v) => v.toLocaleString('pt-BR')} />
                  <YAxis type="number" dataKey="y" name="Preço" unit=" R$" tickFormatter={(v) => `R$ ${v.toFixed(2)}`} />
                  <Tooltip
                    cursor={{ strokeDasharray: '3 3' }}
                    content={({ active, payload }) => {
                      if (!active || !payload || !payload.length) return null;
                      const p = payload[0].payload;
                      return (
                        <div className="bg-white border border-gray-200 rounded-lg shadow p-3 text-sm">
                          <p className="font-semibold">{p.name}</p>
                          <p className="text-gray-500">{p.linha}</p>
                          <p>Volume: {p.x.toLocaleString('pt-BR')} L</p>
                          <p>Preço final: R$ {p.y.toFixed(2)}</p>
                        </div>
                      );
                    }}
                  />
                  <Legend wrapperStyle={{ fontSize: 12 }} />
                  {scatterSeries.map(serie => (
                    <Scatter
                      animationDuration={300} animationEasing="ease-out"
                      key={serie.linha}
                      name={serie.linha}
                      data={serie.itens}
                      fill={entityColorMap[serie.linha] || '#8884d8'}
                      shape={(props) => <ScatterPoint {...props} />}
                    />
                  ))}
                  {regressao ? (
                    <ReferenceLine
                      segment={regressao.segmento}
                      stroke="#ef4444"
                      strokeDasharray="6 4"
                      label={{ value: 'Tendência', position: 'insideTopRight', fontSize: 11 }}
                    />
                  ) : null}
                </ScatterChart>
              </ResponsiveContainer>
            </ChartAoRolar>
          </div>
        </div>
      </div>

      {/* --- Tabela de Dados --- */}
      <div className="bg-white rounded-xl shadow-md overflow-x-auto">
        <h2 className="text-xl font-semibold p-6">Dados Detalhados da Coleta</h2>
        <table className="min-w-full divide-y divide-gray-200">
          <thead className="bg-gray-50">
            <tr>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Data</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Produtor</th>
              <th className="px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase">Litros</th>
            </tr>
          </thead>
          <tbody className="bg-white divide-y divide-gray-200">
            {tablePageRows.map(coleta => (
              <tr key={coleta.id}>
                <td className="px-6 py-4 whitespace-nowrap">{dataBRDeDataISO(coleta.data)}</td>
                <td className="px-6 py-4 whitespace-nowrap">{coleta.produtorName}</td>
                <td className="px-6 py-4 whitespace-nowrap text-right">{coleta.quantidadeLitros} L</td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="flex items-center justify-between px-6 py-4 text-sm text-gray-600">
          <span>
            {tableTotalRows === 0
              ? 'Nenhum registro'
              : `Mostrando ${tablePageStart + 1}-${Math.min(tablePageStart + TABLE_PAGE_SIZE, tableTotalRows)} de ${tableTotalRows}`}
          </span>
          <div className="flex gap-2">
            <button
              type="button"
              className="px-3 py-1 rounded border border-gray-300 disabled:opacity-50"
              onClick={() => setTablePage(p => Math.max(0, p - 1))}
              disabled={tablePage === 0}
            >
              Anterior
            </button>
            <button
              type="button"
              className="px-3 py-1 rounded border border-gray-300 disabled:opacity-50"
              onClick={() => setTablePage(p => Math.min(tableTotalPages - 1, p + 1))}
              disabled={tablePage >= tableTotalPages - 1}
            >
              Próxima
            </button>
          </div>
        </div>
      </div>
      </>
      )}
    </div>
  );
};

export default ReportsPage;
